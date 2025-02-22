/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelmapper.internal;

import org.modelmapper.Converter;
import org.modelmapper.PropertyMap;
import org.modelmapper.TypeMap;
import org.modelmapper.internal.util.Primitives;
import org.modelmapper.internal.util.Types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Jonathan Halterman
 */
public final class TypeMapStore {
  private final Map<TypePair<?, ?>, TypeMap<?, ?>> typeMaps = new ConcurrentHashMap<TypePair<?, ?>, TypeMap<?, ?>>();
  private final Map<TypePair<?, ?>, TypeMap<?, ?>> immutableTypeMaps = Collections.unmodifiableMap(typeMaps);
  private final Object lock = new Object();
  /** Default configuration */
  private final InheritingConfiguration config;

  TypeMapStore(InheritingConfiguration config) {
    this.config = config;
  }

  /**
   * Creates a TypeMap. If {@code converter} is null, the TypeMap is configured with implicit
   * mappings, else the {@code converter} is set against the TypeMap.
   */
  public <S, D> TypeMap<S, D> create(S source, Class<S> sourceType, Class<D> destinationType,
      String typeMapName, InheritingConfiguration configuration, MappingEngineImpl engine) {
    synchronized (lock) {
      TypeMapImpl<S, D> typeMap = new TypeMapImpl<S, D>(sourceType, destinationType, typeMapName,
          configuration, engine);
      if (configuration.isImplicitMappingEnabled()
          && Types.mightContainsProperties(typeMap.getSourceType())
          && Types.mightContainsProperties(typeMap.getDestinationType()))
        ImplicitMappingBuilder.build(source, typeMap, config.typeMapStore, config.converterStore);
      typeMaps.put(TypePair.of(sourceType, destinationType, typeMapName), typeMap);
      return typeMap;
    }
  }

  /**
   * Creates a  empty TypeMap. If {@code converter} is null, the TypeMap is configured with implicit
   * mappings, else the {@code converter} is set against the TypeMap.
   */
  public <S, D> TypeMap<S, D> createEmptyTypeMap(Class<S> sourceType, Class<D> destinationType,
      String typeMapName, InheritingConfiguration configuration, MappingEngineImpl engine) {
    synchronized (lock) {
      TypeMapImpl<S, D> typeMap = new TypeMapImpl<S, D>(sourceType, destinationType, typeMapName,
          configuration, engine);
      typeMaps.put(TypePair.of(sourceType, destinationType, typeMapName), typeMap);
      return typeMap;
    }
  }

  public Collection<TypeMap<?, ?>> get() {
    return immutableTypeMaps.values();
  }

  /**
   * Returns a TypeMap for the {@code sourceType}, {@code destinationType} and {@code typeMapName},
   * else null if none exists.
   */
  @SuppressWarnings("unchecked")
  public <S, D> TypeMap<S, D> get(Class<S> sourceType, Class<D> destinationType, String typeMapName) {
    TypeMap<S, D> typeMap = getTypeMap(sourceType, destinationType, typeMapName);
    if (typeMap != null)
      return typeMap;

    for (TypePair<?, ?> typePair : getPrimitiveWrapperTypePairs(sourceType, destinationType, typeMapName)) {
      typeMap = (TypeMap<S, D>) typeMaps.get(typePair);
      if (typeMap != null)
        return typeMap;
    }

    return null;
  }

  /**
   * Gets or creates a TypeMap. If {@code converter} is null, the TypeMap is configured with
   * implicit mappings, else the {@code converter} is set against the TypeMap.
   */
  public <S, D> TypeMap<S, D> getOrCreate(S source, Class<S> sourceType, Class<D> destinationType,
      String typeMapName, MappingEngineImpl engine) {
    return getOrCreate(source, sourceType, destinationType, typeMapName, null, null,
        engine);
  }

  /**
   * Gets or creates a TypeMap. If {@code converter} is null, the TypeMap is configured with
   * implicit mappings, else the {@code converter} is set against the TypeMap.
   * 
   * @param propertyMap to add mappings for (nullable)
   * @param converter to set (nullable)
   */
  @SuppressWarnings("unchecked")
  public <S, D> TypeMap<S, D> getOrCreate(S source, Class<S> sourceType, Class<D> destinationType,
      String typeMapName, PropertyMap<S, D> propertyMap, Converter<S, D> converter,
      MappingEngineImpl engine) {
    synchronized (lock) {
      TypeMapImpl<S, D> typeMap = getTypeMap(sourceType, destinationType, typeMapName);

      if (typeMap == null) {
        typeMap = new TypeMapImpl<S, D>(sourceType, destinationType, typeMapName, config, engine);
        if (propertyMap != null)
          typeMap.addMappings(propertyMap);
        if (converter == null && config.isImplicitMappingEnabled()
            && Types.mightContainsProperties(typeMap.getSourceType())
            && Types.mightContainsProperties(typeMap.getDestinationType()))
          ImplicitMappingBuilder.build(source, typeMap, config.typeMapStore, config.converterStore);

        if (typeMap.isFullMatching()) {
          typeMaps.put(TypePair.of(sourceType, destinationType, typeMapName), typeMap);
        }
      } else if (propertyMap != null) {
        typeMap.addMappings(propertyMap);
      }

      if (converter != null)
        typeMap.setConverter(converter);
      return typeMap;
    }
  }

  /**
   * Puts a typeMap into store
   *
   * @throws IllegalArgumentException if {@link TypePair} of typeMap is already exists in the store
   */
  public void put(TypeMap<?, ?> typeMap) {
    TypePair<?, ?> typePair = TypePair.of(typeMap.getSourceType(),
        typeMap.getDestinationType(), typeMap.getName());
    synchronized (lock) {
      if (typeMaps.containsKey(typePair))
        throw new IllegalArgumentException("TypeMap exists in the store: " + typePair.toString());
      typeMaps.put(typePair, typeMap);
    }
  }

  /**
   * Puts a typeMap into store
   *
   * @throws IllegalArgumentException if {@link TypePair} of typeMap is already exists in the store
   */
  public <S, D> void put(Class<S> sourceType, Class<D> destinationType, TypeMap<S, ? extends D> typeMap) {
    TypePair<S, D> typePair = TypePair.of(sourceType, destinationType,
        typeMap.getName());
    synchronized (lock) {
      if (typeMaps.containsKey(typePair))
        throw new IllegalArgumentException("TypeMap exists in the store: " + typePair.toString());
      typeMaps.put(typePair, typeMap);
    }
  }

  private <S, D> List<TypePair<?, ?>> getPrimitiveWrapperTypePairs(Class<S> sourceType, Class<D> destinationType, String typeMapName) {
    List<TypePair<?, ?>> typePairs = new ArrayList<TypePair<?, ?>>(1);
    if (Primitives.isPrimitive(sourceType)) {
      typePairs.add(TypePair.of(Primitives.wrapperFor(sourceType), destinationType, typeMapName));
    }
    if (Primitives.isPrimitive(destinationType)) {
      typePairs.add(TypePair.of(sourceType, Primitives.wrapperFor(destinationType), typeMapName));
    }
    if (Primitives.isPrimitive(sourceType) && Primitives.isPrimitiveWrapper(destinationType)) {
      typePairs.add(TypePair.of(Primitives.wrapperFor(sourceType), Primitives.wrapperFor(destinationType), typeMapName));
    }
    return typePairs;
  }

  @SuppressWarnings("unchecked")
  private <S, D> TypeMapImpl<S, D> getTypeMap(Class<S> sourceType, Class<D> destinationType, String typeMapName) {
    TypePair<S, D> typePair = TypePair.of(sourceType, destinationType, typeMapName);

    TypeMapImpl<S, D> typeMap = (TypeMapImpl<S, D>) typeMaps.get(typePair);
    if (typeMap == null && isAnonymousEnumSubclass(sourceType)) {
      typeMap = (TypeMapImpl<S, D>) typeMaps.get(
              TypePair.of((Class<S>) sourceType.getSuperclass(), destinationType, typeMapName)
      );
    }

    return typeMap;
  }

  private <S> boolean isAnonymousEnumSubclass(Class<S> sourceType) {
    return sourceType.isAnonymousClass() && sourceType.getSuperclass().isEnum();
  }

}
