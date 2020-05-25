/*
 * Copyright 2020 the original author or authors.
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
package io.github.arnelimburg.tinyinjector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Scope;

/**
 * @author Arne Limburg
 */
public class Injector {

  private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];
  
  private Map<InjectionPoint, Class<?>> injectableTypes = new HashMap<>();
  private Map<InjectionPoint, Object> injectableInstances = new HashMap<>();
  private Set<Member> injectedStaticMembers = new HashSet<>();

  public Injectable injecting(Object object) {
    return new InjectableInstance(object);
  }
  
  public Injectable injecting(Class<?> type) {
    return new InjectableType(type);
  }

  @SuppressWarnings("unchecked")
  public <I> I getInstance(Class<I> type) {
    return (I)getInstance(type, EMPTY_ANNOTATIONS);
  }

  public Object getInstance(Type type, Annotation... qualifiers) {
    return getInstance(new InjectionPoint(type, qualifiers));
  }
  
  private Object getInstance(InjectionPoint injectionPoint) {
    Object instance = injectableInstances.get(injectionPoint);
    if (instance != null) {
      return instance;
    }
    Class<?> targetType = getType(injectionPoint);
    if (Provider.class.equals(targetType)) {
      Provider<?> provider = new InjectorProvider<Object>(injectionPoint);
      injectableInstances.put(injectionPoint, provider);
      return provider;
    }
    try {
      Constructor<?> constructor = getInjectableConstructor(targetType);
      instance = constructor.newInstance(getInjectableDependencies(constructor));
      if (isScoped(targetType)) {
        injectableInstances.put(injectionPoint, instance);
      }
      inject(instance);
      return instance;
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(e);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    } catch (InstantiationException e) {
      throw new IllegalStateException(e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
  
  private Constructor<?> getInjectableConstructor(Class<?> type) throws NoSuchMethodException, SecurityException {
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (constructor.isAnnotationPresent(Inject.class)) {
        constructor.setAccessible(true);
        return constructor;
      }
    }
    return type.getDeclaredConstructor();
  }

  private void inject(Object instance) throws IllegalAccessException, InvocationTargetException {
    inject(instance, instance.getClass(), new HashSet<Method>());
  }

  private void inject(Object instance, Class<?> type, Collection<Method> overriddenMethods) throws IllegalAccessException, InvocationTargetException {
    if (type == null) {
      return;
    }
    Collection<Method> newOverriddenMethods = new HashSet<Method>(overriddenMethods);
    for (Method method: type.getDeclaredMethods()) {
      if (!Modifier.isStatic(method.getModifiers())) {
        newOverriddenMethods.add(method);
      }
    }
    inject(instance, type.getSuperclass(), newOverriddenMethods);
    for (Field field: type.getDeclaredFields()) {
      if (field.isAnnotationPresent(Inject.class) && !injectedStaticMembers.contains(field)) { 
        field.setAccessible(true);
        field.set(instance, getInjectableDependency(new InjectionPoint(field.getGenericType(), field.getAnnotations())));
        if (Modifier.isStatic(field.getModifiers())) {
          injectedStaticMembers.add(field);
        }
      }
    }
    for (Method method: type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Inject.class) && !isOverridden(method, overriddenMethods) && !injectedStaticMembers.contains(method)) {
        method.setAccessible(true);
        method.invoke(instance, getInjectableDependencies(method));
        if (Modifier.isStatic(method.getModifiers())) {
          injectedStaticMembers.add(method);
        }
      }
    }
  }

  private Object[] getInjectableDependencies(Constructor<?> constructor) {
    InjectionPoint[] injectionPoints = new InjectionPoint[constructor.getParameterTypes().length];
    for (int i = 0; i < injectionPoints.length; i++) {
      injectionPoints[i] = new InjectionPoint(constructor.getGenericParameterTypes()[i], constructor.getParameterAnnotations()[i]);
    }
    return getInjectableDependencies(injectionPoints);
  }

  private Object[] getInjectableDependencies(Method method) {
    InjectionPoint[] injectionPoints = new InjectionPoint[method.getParameterTypes().length];
    for (int i = 0; i < injectionPoints.length; i++) {
      injectionPoints[i] = new InjectionPoint(method.getGenericParameterTypes()[i], method.getParameterAnnotations()[i]);
    }
    return getInjectableDependencies(injectionPoints);
  }

  private Object[] getInjectableDependencies(InjectionPoint...  injectionPoints) {
    Object[] dependencies = new Object[injectionPoints.length];
    for (int i = 0; i < dependencies.length; i++) {
      dependencies[i] = getInjectableDependency(injectionPoints[i]);
    }
    return dependencies;
  }

  private Object getInjectableDependency(InjectionPoint injectionPoint) {
    Object instance = injectableInstances.get(injectionPoint);
    if (instance != null) {
      return instance;
    }
    return getInstance(injectionPoint);
  }

  private Class<?> getType(InjectionPoint injectionPoint) {
    Class<?> targetClass = injectableTypes.get(injectionPoint);
    if (targetClass != null) {
      return targetClass;
    }
    try {
      return getType(injectionPoint.type);
    } catch (IllegalStateException e) {
      throw new IllegalStateException("Cannot resolve dependency " + injectionPoint, e);
    }
  }

  private Class<?> getType(Type type) {
    if (type instanceof Class) {
      return (Class<?>)type;
    }
    if (type instanceof ParameterizedType) {
      return getType(((ParameterizedType)type).getRawType());
    }
    throw new IllegalStateException("Cannot resolve type " + type);
  }

  private Annotation[] getQualifiers(Annotation... annotations) {
    List<Annotation> qualifiers = new ArrayList<Annotation>();
    for (Annotation annotation: annotations) {
      if (isQualifier(annotation)) {
        qualifiers.add(annotation);
      }
    }
    return qualifiers.toArray(new Annotation[qualifiers.size()]);
  }

  private boolean isScoped(Class<?> type) {
    for (Annotation annotation: type.getAnnotations()) {
      if (isScope(annotation)) {
        return true;
      }
    }
    return false;
  }

  private boolean isScope(Annotation annotation) {
    return annotation.annotationType().isAnnotationPresent(Scope.class);
  }

  private boolean isQualifier(Annotation annotation) {
    return annotation.annotationType().isAnnotationPresent(Qualifier.class);
  }

  private boolean isOverridden(Method method, Collection<Method> overriddenMethods) {
    if (Modifier.isPrivate(method.getModifiers())) {
      return false;
    }
    overriddenMethods: for (Method overriddenMethod: overriddenMethods) {
      if (!overriddenMethod.getName().equals(method.getName())) {
        continue;
      }
      Class<?>[] parameterTypes = method.getParameterTypes();
      Class<?>[] overriddenParameterTypes = overriddenMethod.getParameterTypes();
      if (parameterTypes.length != overriddenParameterTypes.length) {
        continue;
      }
      for (int i = 0; i < parameterTypes.length; i++) {
        if (!parameterTypes[i].equals(overriddenParameterTypes[i])) {
          continue overriddenMethods;
        }
      }
      if (Modifier.isProtected(method.getModifiers()) || Modifier.isPublic(method.getModifiers())) {
        return true;
      }
      if (method.getDeclaringClass().getPackage().equals(overriddenMethod.getDeclaringClass().getPackage())) {
        return true;
      }
    }
    return false;
  }

  public interface Injectable {
    Injector into(Type type, Annotation... qualifiers);
  }

  private class InjectableInstance implements Injectable {
    
    private Object instance;
    
    private InjectableInstance(Object object) {
      this.instance = object;
    }

    public Injector into(Type type, Annotation... qualifiers) {
      injectableInstances.put(new InjectionPoint(type, qualifiers), instance);
      return Injector.this;
    }
  }

  private class InjectableType implements Injectable {
    
    private Class<?> type;
    
    private InjectableType(Class<?> type) {
      this.type = type;
    }

    public Injector into(Type type, Annotation... qualifiers) {
      injectableTypes.put(new InjectionPoint(type, qualifiers), this.type);
      return Injector.this;
    }
  }

  private class InjectionPoint {
    
    private Type type;
    private Annotation[] qualifiers;
    
    private InjectionPoint(Type type, Annotation... annotations) {
      this.type = type;
      this.qualifiers = getQualifiers(annotations);
    }

    public int hashCode() {
      return type.hashCode() ^ Arrays.hashCode(qualifiers);
    }

    public boolean equals(Object object) {
      if (!(object instanceof InjectionPoint)) {
        return false;
      }
      InjectionPoint injectionPoint = (InjectionPoint)object;
      if (!type.equals(injectionPoint.type)) {
        return false;
      }
      if (qualifiers.length != injectionPoint.qualifiers.length) {
        return false;
      }
      for (int i = 0; i < qualifiers.length; i++) {
        Annotation a1 = qualifiers[i];
        Annotation a2 = injectionPoint.qualifiers[i];
        if (!equals(a1, a2)) {
          return false;
        }
      }
      return true;
    }
    
    public String toString() {
      StringBuilder builder = new StringBuilder();
      for (Annotation qualifier: qualifiers) {
        builder.append(qualifier).append(' ');
      }
      return builder.append(type).toString();
    }

    private boolean equals(Annotation a1, Annotation a2) {
      if (a1 == a2) {
        return true;
      }
      if (!a1.annotationType().equals(a2.annotationType())) {
        return false;
      }
      for (Method method: a1.annotationType().getMethods()) {
        if (isObjectMethod(method)) {
          continue;
        }
        try {
          Object o1 = method.invoke(a1);
          Object o2 = method.invoke(a2);
          if (o1 == o2) {
            return true;
          }
          if (o1 == null && o2 != null) {
            return false;
          }
          if ((o1 instanceof Annotation) && (o2 instanceof Annotation) && !equals((Annotation)o1, (Annotation)o2)) {
            return false;
          }
          if (o1 != null && !o1.equals(o2)) {
            return false;
          }
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
          throw new IllegalArgumentException(e);
        }
      }
      return true;
    }
    
    private boolean isObjectMethod(Method method) {
      if (method.getParameterTypes().length > 0) {
        return true;
      }
      return "hashCode".equals(method.getName()) || "toString".equals(method.getName());
    }
  }

  private class InjectorProvider<T> implements Provider<T> {
    
    private InjectionPoint injectionPoint;
    
    private InjectorProvider(InjectionPoint injectionPoint) {
      ParameterizedType type = (ParameterizedType)injectionPoint.type;
      Type targetType = type.getActualTypeArguments()[0];
      this.injectionPoint = new InjectionPoint(targetType, injectionPoint.qualifiers);
    }

    @Override
    public T get() {
      return (T)getInstance(injectionPoint.type, injectionPoint.qualifiers);
    }
  }
}
