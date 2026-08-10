package dev.a2dprouter.system;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ReflectionAccess {
    private ReflectionAccess() {
    }

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }

    static Constructor<?> constructor(Class<?> owner, Class<?>... parameters)
            throws NoSuchMethodException {
        Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor;
    }
}
