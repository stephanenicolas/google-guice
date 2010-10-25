/**
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.throwingproviders;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import com.google.inject.Binder;
import com.google.inject.Exposed;
import com.google.inject.Key;
import com.google.inject.PrivateBinder;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.ScopedBindingBuilder;
import com.google.inject.internal.util.ImmutableSet;
import com.google.inject.internal.util.StackTraceElements;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.HasDependencies;
import com.google.inject.throwingproviders.ThrowingProviderBinder.SecondaryBinder;

/**
 * A provider that invokes a method and returns its result.
 *
 * @author sameb@google.com (Sam Berlin)
 */
class ThrowingProviderMethod<T> implements ThrowingProvider<T, Exception>, HasDependencies {
  private final Key<T> key;
  private final Class<? extends Annotation> scopeAnnotation;
  private final Object instance;
  private final Method method;
  private final ImmutableSet<Dependency<?>> dependencies;
  private final List<Provider<?>> parameterProviders;
  private final boolean exposed;
  private final Class<? extends ThrowingProvider> throwingProvider;
  private final List<TypeLiteral<?>> exceptionTypes;

  ThrowingProviderMethod(
      Key<T> key,
      Method method,
      Object instance,
      ImmutableSet<Dependency<?>> dependencies,
      List<Provider<?>> parameterProviders,
      Class<? extends Annotation> scopeAnnotation,
      Class<? extends ThrowingProvider> throwingProvider,
      List<TypeLiteral<?>> exceptionTypes) {
    this.key = key;
    this.scopeAnnotation = scopeAnnotation;
    this.instance = instance;
    this.dependencies = dependencies;
    this.method = method;
    this.parameterProviders = parameterProviders;
    this.exposed = method.isAnnotationPresent(Exposed.class);
    this.throwingProvider = throwingProvider;
    this.exceptionTypes = exceptionTypes;

    method.setAccessible(true);
  }

  public Key<T> getKey() {
    return key;
  }

  public Method getMethod() {
    return method;
  }

  public void configure(Binder binder) {
    binder = binder.withSource(method);

    SecondaryBinder<?> sbinder = 
      ThrowingProviderBinder.create(binder)
        .bind(throwingProvider, key.getTypeLiteral().getType());
    if(key.getAnnotation() != null) {
      sbinder = sbinder.annotatedWith(key.getAnnotation());
    } else if(key.getAnnotationType() != null) {
      sbinder = sbinder.annotatedWith(key.getAnnotationType());
    } 
    ScopedBindingBuilder sbbuilder = sbinder.toProviderMethod(this);
    if(scopeAnnotation != null) {
      sbbuilder.in(scopeAnnotation);
    }

    if (exposed) {
      // the cast is safe 'cause the only binder we have implements PrivateBinder. If there's a
      // misplaced @Exposed, calling this will add an error to the binder's error queue
      ((PrivateBinder) binder).expose(key);
    }

    // Validate the exceptions in the method match the exceptions
    // in the ThrowingProvider.
    for(TypeLiteral<?> exType : exceptionTypes) {
      // Ignore runtime exceptions.
      if(RuntimeException.class.isAssignableFrom(exType.getRawType())) {
        continue;
      }
      
      if(sbinder.getExceptionType() != null) {
        if (!sbinder.getExceptionType().isAssignableFrom(exType.getRawType())) {
          binder.addError(
              "%s is not compatible with the exception (%s) declared in the ThrowingProvider interface (%s)",
              exType.getRawType(), sbinder.getExceptionType(), throwingProvider);
        }
      }
    }
  }

  public T get() throws Exception {
    Object[] parameters = new Object[parameterProviders.size()];
    for (int i = 0; i < parameters.length; i++) {
      parameters[i] = parameterProviders.get(i).get();
    }

    try {
      // We know this cast is safe becase T is the method's return type.
      @SuppressWarnings({ "unchecked", "UnnecessaryLocalVariable" })
      T result = (T) method.invoke(instance, parameters);
      return result;
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    } catch (InvocationTargetException e) {
      Throwable t = e.getCause();
      if(t instanceof Exception) {
        throw (Exception)t;
      } else if(t instanceof Error) {
        throw (Error)t;
      } else {
        throw new IllegalStateException(t);
      }
    }
  }

  public Set<Dependency<?>> getDependencies() {
    return dependencies;
  }

  @Override public String toString() {
    return "@ThrowingProvides " + StackTraceElements.forMember(method).toString();
  }
}