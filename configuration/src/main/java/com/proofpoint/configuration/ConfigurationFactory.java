package com.proofpoint.configuration;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;
import com.google.inject.ConfigurationException;
import com.proofpoint.configuration.ConfigurationMetadata.AttributeMetadata;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static com.proofpoint.configuration.Problems.exceptionFor;
import static java.lang.String.format;

public class ConfigurationFactory
{
    private final Map<String, String> properties;
    private final Problems.Monitor monitor;
    private final ConcurrentMap<Class<?>, ConfigurationMetadata<?>> metadataCache;

    public ConfigurationFactory(Map<String, String> properties, final Problems.Monitor monitor)
    {
        this.monitor = monitor;
        this.properties = ImmutableMap.copyOf(properties);

        metadataCache = new MapMaker().weakKeys().weakValues().makeComputingMap(new Function<Class<?>, ConfigurationMetadata<?>>()
        {
            @Override
            public ConfigurationMetadata<?> apply(Class<?> configClass)
            {
                return ConfigurationMetadata.getConfigurationMetadata(configClass, monitor);
            }
        });
    }

    public ConfigurationFactory(Map<String, String> properties)
    {
        this(properties, Problems.NULL_MONITOR);
    }

    public Map<String, String> getProperties()
    {
        return properties;
    }

    public <T> T build(Class<T> configClass)
    {
        return build(configClass, "", null);
    }

    public <T> T build(Class<T> configClass, String prefix, T instance)
    {
        if (configClass == null) {
            throw new NullPointerException("configClass is null");
        }

        if (prefix == null) {
            prefix = "";
        }
        else if (!prefix.isEmpty()) {
            prefix = prefix + ".";
        }

        ConfigurationMetadata<T> configurationMetadata = (ConfigurationMetadata<T>) metadataCache.get(configClass);
        configurationMetadata.getProblems().throwIfHasErrors();

        if (instance == null) {
            instance = newInstance(configurationMetadata);
        }

        Problems problems = new Problems(monitor);
        for (AttributeMetadata attribute : configurationMetadata.getAttributes().values()) {
            try {
                setConfigProperty(instance, attribute, prefix);
            }
            catch (InvalidConfigurationException e) {
                problems.addError(e.getCause(), e.getMessage());
            }
        }

        problems.throwIfHasErrors();

        return instance;
    }

    private <T> T newInstance(ConfigurationMetadata<T> configurationMetadata)
    {
        try {
            return configurationMetadata.getConstructor().newInstance();
        }
        catch (Throwable e) {
            if (e instanceof InvocationTargetException && e.getCause() != null) {
                e = e.getCause();
            }
            throw exceptionFor(e, "Error creating instance of configuration class [%s]", configurationMetadata.getConfigClass().getName());
        }
    }

    private <T> void setConfigProperty(T instance, AttributeMetadata attribute, String prefix)
            throws InvalidConfigurationException
    {
        // Get property value
        Object value = getPropertyValue(attribute, prefix);

        // If we did not get a value, do not call the setter
        if (value == null) {
            return;
        }

        try {
            attribute.getSetter().invoke(instance, value);
        }
        catch (Throwable e) {
            if (e instanceof InvocationTargetException && e.getCause() != null) {
                e = e.getCause();
            }
            throw new InvalidConfigurationException(e, "Error invoking configuration method [%s]", attribute.getSetter().toGenericString());
        }
    }

    private String findOperativeProperty(AttributeMetadata attribute, String prefix)
            throws ConfigurationException
    {
        String operativeName = attribute.getPropertyName() == null ? null : prefix + attribute.getPropertyName();
        String operativeValue = operativeName == null ? null : properties.get(operativeName);

        Problems problems = new Problems(monitor);

        for (String deprecatedName : attribute.getDeprecatedNames()) {
            String fullName = prefix + deprecatedName;
            String value = properties.get(fullName);
            if (value != null) {
                String deprecatedReplacement
                        = attribute.getPropertyName() == null ? "There is no replacement." : format("Use '%s' instead.", prefix + attribute.getPropertyName());
                problems.addWarning("Configuration property '%s' has been deprecated. " + deprecatedReplacement, fullName);

                if (operativeValue == null) {
                    operativeValue = value;
                    operativeName = fullName;
                }
                else if (!value.equals(operativeValue)) {
                    problems.addError("Value for property '%s' (=%s) conflicts with property '%s' (=%s)", fullName, value, operativeName, operativeValue);
                }
            }
        }

        problems.throwIfHasErrors();
        return operativeName;
    }

    private Object getPropertyValue(AttributeMetadata attribute, String prefix)
            throws InvalidConfigurationException
    {
        // Get the property value
        String propertyName = findOperativeProperty(attribute, prefix);
        String value = propertyName == null ? null : properties.get(propertyName);

        if (value == null) {
            return null;
        }

        // coerce the property value to the final type
        Class<?> propertyType = attribute.getSetter().getParameterTypes()[0];

        Object finalValue = coerce(propertyType, value);
        if (finalValue == null) {
            throw new InvalidConfigurationException(format("Could not coerce value '%s' to %s for attribute '%s' (property '%s') in [%s]",
                    value,
                    propertyType.getName(),
                    attribute.getName(),
                    propertyName,
                    attribute.getSetter().toGenericString()));
        }
        return finalValue;
    }

    private static Object coerce(Class<?> type, String value)
    {
        if (type.isPrimitive() && value == null) {
            return null;
        }


        try {
            if (String.class.isAssignableFrom(type)) {
                return value;
            }
            else if (Boolean.class.isAssignableFrom(type) || Boolean.TYPE.isAssignableFrom(type)) {
                return Boolean.valueOf(value);
            }
            else if (Byte.class.isAssignableFrom(type) || Byte.TYPE.isAssignableFrom(type)) {
                return Byte.valueOf(value);
            }
            else if (Short.class.isAssignableFrom(type) || Short.TYPE.isAssignableFrom(type)) {
                return Short.valueOf(value);
            }
            else if (Integer.class.isAssignableFrom(type) || Integer.TYPE.isAssignableFrom(type)) {
                return Integer.valueOf(value);
            }
            else if (Long.class.isAssignableFrom(type) || Long.TYPE.isAssignableFrom(type)) {
                return Long.valueOf(value);
            }
            else if (Float.class.isAssignableFrom(type) || Float.TYPE.isAssignableFrom(type)) {
                return Float.valueOf(value);
            }
            else if (Double.class.isAssignableFrom(type) || Double.TYPE.isAssignableFrom(type)) {
                return Double.valueOf(value);
            }
        }
        catch (Exception ignored) {
            // ignore the random exceptions from the built in types
            return null;
        }

        // Look for a static valueOf(String) method
        try {
            Method valueOf = type.getMethod("valueOf", String.class);
            if (valueOf.getReturnType().isAssignableFrom(type)) {
                return valueOf.invoke(null, value);
            }
        }
        catch (Throwable ignored) {
        }

        // Look for a constructor taking a string
        try {
            Constructor<?> constructor = type.getConstructor(String.class);
            return constructor.newInstance(value);
        }
        catch (Throwable ignored) {
        }

        return null;
    }
}
