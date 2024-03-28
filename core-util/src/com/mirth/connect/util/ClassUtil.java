package com.mirth.connect.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ClassUtil {
    
    private static Logger logger = LogManager.getLogger(ClassUtil.class);

    public static <T> T createInstanceOrDefault(Class<T> superClass, Class<?> clazz, T defaultValue) {
        try {
            return createInstance(superClass, clazz);
        } catch (Exception e) {
            logger.error(e);
            return defaultValue;
        }
    }
            
    @SuppressWarnings("unchecked")
    public static <T> T createInstance(Class<T> superClass, Class<?> clazz) {
        if (clazz != null) {
            try {
                return (T) clazz.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Unable to create instance of " + superClass.getName() + ": " + clazz.getName(), e);
            }
        } else {
            throw new RuntimeException("Unable to create instance of " + superClass.getName() + ": No concrete class provided.");
        }
    }
}
