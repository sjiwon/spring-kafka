/*
 * Copyright 2017-present the original author or authors.
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

package org.springframework.kafka.support.mapping;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.Nullable;

import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.PatternMatchUtils;

/**
 * Jackson 2 type mapper.
 *
 * @author Mark Pollack
 * @author Sam Nelson
 * @author Andreas Asplund
 * @author Artem Bilan
 * @author Gary Russell
 *
 * @since 2.1
 */
public class DefaultJackson2JavaTypeMapper extends AbstractJavaTypeMapper
		implements Jackson2JavaTypeMapper {

	private static final List<String> TRUSTED_PACKAGES = List.of("java.util", "java.lang");

	private final Set<String> trustedPackages = new LinkedHashSet<>(TRUSTED_PACKAGES);

	private volatile TypePrecedence typePrecedence = TypePrecedence.INFERRED;

	/**
	 * Return the precedence.
	 * @return the precedence.
	 * @see #setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence)
	 */
	@Override
	public TypePrecedence getTypePrecedence() {
		return this.typePrecedence;
	}

	@Override
	public void setTypePrecedence(TypePrecedence typePrecedence) {
		Assert.notNull(typePrecedence, "'typePrecedence' cannot be null");
		this.typePrecedence = typePrecedence;
	}

	/**
	 * Specify a set of packages to trust during deserialization.
	 * The asterisk ({@code *}) means trust all.
	 * @param packagesToTrust the trusted Java packages for deserialization
	 */
	@Override
	public void addTrustedPackages(String... packagesToTrust) {
		if (this.trustedPackages.isEmpty()) {
			return;
		}
		if (packagesToTrust != null) {
			for (String trusted : packagesToTrust) {
				if ("*".equals(trusted)) {
					this.trustedPackages.clear();
					break;
				}
				else {
					this.trustedPackages.add(trusted);
				}
			}
		}
	}

	@Override
	public @Nullable JavaType toJavaType(Headers headers) {
		String typeIdHeader = retrieveHeaderAsString(headers, getClassIdFieldName());

		if (typeIdHeader != null) {

			JavaType classType = getClassIdType(typeIdHeader);
			if (!classType.isContainerType() || classType.isArrayType()) {
				return classType;
			}

			JavaType contentClassType = getClassIdType(retrieveHeader(headers, getContentClassIdFieldName()));
			if (classType.getKeyType() == null) {
				return TypeFactory.defaultInstance()
						.constructCollectionLikeType(classType.getRawClass(), contentClassType);
			}

			JavaType keyClassType = getClassIdType(retrieveHeader(headers, getKeyClassIdFieldName()));
			return TypeFactory.defaultInstance()
					.constructMapLikeType(classType.getRawClass(), keyClassType, contentClassType);
		}

		return null;
	}

	private JavaType getClassIdType(String classId) {
		if (getIdClassMapping().containsKey(classId)) {
			return TypeFactory.defaultInstance().constructType(getIdClassMapping().get(classId));
		}
		else {
			try {
				if (!isTrustedPackage(classId)) {
					throw new IllegalArgumentException("The class '" + classId
							+ "' is not in the trusted packages: "
							+ this.trustedPackages + ". "
							+ "If you believe this class is safe to deserialize, please provide its name. "
							+ "If the serialization is only done by a trusted source, you can also enable "
							+ "trust all (*).");
				}
				else {
					return TypeFactory.defaultInstance()
							.constructType(ClassUtils.forName(classId, getClassLoader()));
				}
			}
			catch (ClassNotFoundException e) {
				throw new MessageConversionException("failed to resolve class name. Class not found ["
						+ classId + "]", e);
			}
			catch (LinkageError e) {
				throw new MessageConversionException("failed to resolve class name. Linkage error ["
						+ classId + "]", e);
			}
		}
	}

	private boolean isTrustedPackage(String requestedType) {
		if (!this.trustedPackages.isEmpty()) {
			String packageName = ClassUtils.getPackageName(requestedType).replaceFirst("\\[L", "");
			for (String trustedPackage : this.trustedPackages) {
				if (PatternMatchUtils.simpleMatch(trustedPackage, packageName)) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	@Override
	public void fromJavaType(JavaType javaType, Headers headers) {
		String classIdFieldName = getClassIdFieldName();
		if (headers.lastHeader(classIdFieldName) != null) {
			removeHeaders(headers);
		}

		addHeader(headers, classIdFieldName, javaType.getRawClass());

		if (javaType.isContainerType() && !javaType.isArrayType()) {
			addHeader(headers, getContentClassIdFieldName(), javaType.getContentType().getRawClass());
		}

		if (javaType.getKeyType() != null) {
			addHeader(headers, getKeyClassIdFieldName(), javaType.getKeyType().getRawClass());
		}
	}

	@Override
	public void fromClass(Class<?> clazz, Headers headers) {
		fromJavaType(TypeFactory.defaultInstance().constructType(clazz), headers);

	}

	@Override
	public @Nullable Class<?> toClass(Headers headers) {
		JavaType javaType = toJavaType(headers);
		return javaType == null ? null : javaType.getRawClass();
	}

	@Override
	public void removeHeaders(Headers headers) {
		try {
			headers.remove(getClassIdFieldName());
			headers.remove(getContentClassIdFieldName());
			headers.remove(getKeyClassIdFieldName());
		}
		catch (Exception e) { // NOSONAR
			// NOSONAR
		}
	}

}
