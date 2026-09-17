/*
 * Copyright 2013-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.nacos.configdata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.NacosPropertiesPrefixer;
import com.alibaba.cloud.nacos.NacosPropertySourceRepository;
import com.alibaba.cloud.nacos.client.NacosPropertySource;
import com.alibaba.cloud.nacos.parser.NacosDataParserHandler;
import com.alibaba.cloud.nacos.refresh.NacosSnapshotConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.apache.commons.logging.Log;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.PropertySource;

import static com.alibaba.cloud.nacos.configdata.ConfigPreference.LOCAL;
import static com.alibaba.cloud.nacos.configdata.ConfigPreference.REMOTE;
import static com.alibaba.cloud.nacos.configdata.NacosConfigDataResource.NacosItemConfig;
import static org.springframework.boot.context.config.ConfigData.Option;

/**
 * Implementation of {@link ConfigDataLoader}.
 *
 * <p>
 * Load {@link ConfigData} via {@link NacosConfigDataResource}
 *
 * @author freeman
 * @since 2021.0.1.0
 */
public class NacosConfigDataLoader implements ConfigDataLoader<NacosConfigDataResource> {

	private final Log log;

	public NacosConfigDataLoader(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(getClass());
	}

	@Override
	public @Nullable ConfigData load(ConfigDataLoaderContext context,
			NacosConfigDataResource resource) {
		return doLoad(context, resource);
	}

	public @Nullable ConfigData doLoad(ConfigDataLoaderContext context,
			NacosConfigDataResource resource) {
		NacosConfigManager configManager = getBean(context, NacosConfigManager.class);
		if (configManager == null) {
			throw new IllegalStateException("NacosConfigManager not available");
		}
		ConfigService configService = configManager.getConfigService();
		NacosConfigProperties properties = getBean(context,
				NacosConfigProperties.class);
		if (properties == null) {
			throw new IllegalStateException("NacosConfigProperties not available");
		}

		NacosItemConfig config = resource.getConfig();

		// Fetch the raw content from nacos. A failure here means the config is genuinely
		// unavailable, so it keeps honoring the `optional:` semantics.
		String configContent;
		try {
			configContent = fetchConfig(configService, config.getGroup(),
					config.getDataId(), properties.getTimeout(),
					properties.getNamespace());
		}
		catch (Exception e) {
			log.error("Error getting properties from nacos: " + resource, e);
			if (!resource.isOptional()) {
				throw new ConfigDataResourceNotFoundException(resource, e);
			}
			return null;
		}

		// Parse the fetched content. A failure here means the config exists but its
		// content is broken, so surface the real cause instead of reporting the resource
		// as "not found", which would mislead users into adding `optional:` and silently
		// skipping a genuinely broken config.
		List<PropertySource<?>> propertySources;
		try {
			propertySources = parseConfig(config.getGroup(), config.getDataId(),
					config.getSuffix(), configContent);
		}
		catch (Exception e) {
			log.error("Error parsing config from nacos: " + resource, e);
			throw new NacosConfigParseException(config.getDataId(), config.getGroup(), e);
		}

		NacosPropertySource propertySource = new NacosPropertySource(propertySources,
				config.getGroup(), config.getDataId(), new Date(),
				config.isRefreshEnabled());
		propertySource.setSuffix(config.getSuffix());

		NacosPropertySourceRepository.collectNacosPropertySource(propertySource);

		return new ConfigData(Collections.singletonList(propertySource),
				getOptions(context, resource));
	}

	private Option[] getOptions(ConfigDataLoaderContext context,
			NacosConfigDataResource resource) {
		List<Option> options = new ArrayList<>();
		options.add(Option.IGNORE_IMPORTS);
		options.add(Option.IGNORE_PROFILES);
		if (getPreference(context, resource) == REMOTE) {
			// mark it as 'PROFILE_SPECIFIC' config, it has higher priority,
			// will override the none profile specific config.
			// fixed https://github.com/alibaba/spring-cloud-alibaba/issues/2455
			options.add(Option.PROFILE_SPECIFIC);
		}
		return options.toArray(new Option[0]);
	}

	private ConfigPreference getPreference(ConfigDataLoaderContext context,
			NacosConfigDataResource resource) {
		Binder binder = context.getBootstrapContext().get(Binder.class);
		if (binder == null) {
			throw new IllegalStateException("Binder not available in BootstrapContext");
		}
		String prefix = NacosPropertiesPrefixer.getPrefix(binder);


		ConfigPreference preference = binder
				.bind(prefix + ".config.preference", ConfigPreference.class)
				.orElse(LOCAL);
		String specificPreference = resource.getConfig().getPreference();
		if (specificPreference != null) {
			try {
				preference = ConfigPreference.valueOf(specificPreference.toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ignore) {
				// illegal preference value, just ignore.
				log.error(String.format(
						"illegal preference value: %s, using default preference: %s",
						specificPreference, preference));
			}
		}
		return preference;
	}

	private String fetchConfig(ConfigService configService, String group, String dataId,
			long timeout, @Nullable String namespace) throws NacosException {
		String config = NacosSnapshotConfigManager.getAndRemoveConfigSnapshot(namespace,
				dataId, group);
		if (config == null) {
			config = configService.getConfig(dataId, group, timeout);
		}
		else {
			log.debug(String.format(
					"[Nacos Config] Load config from snapshot[dataId=%s, group=%s]",
					dataId, group));
		}
		logLoadInfo(group, dataId, config);
		return config;
	}

	private List<PropertySource<?>> parseConfig(String group, String dataId,
			String suffix, String configContent) throws IOException {
		// fixed issue: https://github.com/alibaba/spring-cloud-alibaba/issues/2906 .
		String configName = group + "@" + dataId;
		return NacosDataParserHandler.getInstance()
				.parseNacosData(configName, configContent, suffix);
	}

	private void logLoadInfo(String group, String dataId, String config) {
		if (config != null) {
			log.info(String.format(
					"[Nacos Config] Load config[dataId=%s, group=%s] success", dataId,
					group));
		}
		else {
			log.warn(String.format("[Nacos Config] config[dataId=%s, group=%s] is empty",
					dataId, group));
		}
		if (log.isDebugEnabled()) {
			log.debug(String.format(
					"[Nacos Config] config[dataId=%s, group=%s] content: \n%s", dataId,
					group, config));
		}
	}

	@Nullable
	protected <T> T getBean(ConfigDataLoaderContext context, Class<? extends @Nullable T> type) {

		if (context.getBootstrapContext().isRegistered(type)) {
			return Objects.requireNonNull(context.getBootstrapContext().get(type));
		}

		return null;
	}

}
