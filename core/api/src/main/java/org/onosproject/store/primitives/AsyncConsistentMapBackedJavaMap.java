/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.store.primitives;

import com.google.common.base.Throwables;
import com.google.common.collect.Collections2;
import com.google.common.collect.Maps;
import org.onosproject.store.service.AsyncConsistentMap;
import org.onosproject.store.service.ConsistentMapException;
import org.onosproject.store.service.DistributedPrimitive;
import org.onosproject.store.service.Versioned;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Standard java {@link Map} backed by a {@link AsyncConsistentMap}.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class AsyncConsistentMapBackedJavaMap<K, V> implements Map<K, V> {

    private final AsyncConsistentMap<K, V> backingMap;
    private final long operationTimeoutMillis = DistributedPrimitive.DEFAULT_OPERTATION_TIMEOUT_MILLIS;

    public AsyncConsistentMapBackedJavaMap(AsyncConsistentMap<K, V> backingMap) {
        this.backingMap = backingMap;
    }

    @Override
    public int size() {
        return complete(backingMap.size());
    }

    @Override
    public boolean isEmpty() {
        return complete(backingMap.isEmpty());
    }

    @Override
    public boolean containsKey(Object key) {
        return complete(backingMap.containsKey((K) key));
    }

    @Override
    public boolean containsValue(Object value) {
        return complete(backingMap.containsValue((V) value));
    }

    @Override
    public V get(Object key) {
        return Versioned.valueOrNull(complete(backingMap.get((K) key)));
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return Versioned.valueOrElse(complete(backingMap.get((K) key)), defaultValue);
    }

    @Override
    public V put(K key, V value) {
        backingMap.put(key, value);
        return value;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return Versioned.valueOrNull(complete(backingMap.putIfAbsent(key, value)));
    }

    @Override
    public V remove(Object key) {
        backingMap.remove((K) key);
        return null;
    }

    @Override
    public boolean remove(Object key, Object value) {
        return complete(backingMap.remove((K) key, (V) value));
    }

    @Override
    public V replace(K key, V value) {
        return Versioned.valueOrNull(complete(backingMap.replace(key, value)));
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return complete(backingMap.replace(key, oldValue, newValue));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        m.forEach((k, v) -> {
            backingMap.put(k, v);
        });
    }

    @Override
    public void clear() {
        backingMap.clear();
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return Versioned.valueOrNull(complete(backingMap.compute(key, remappingFunction)));
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        return Versioned.valueOrNull(complete(backingMap.computeIfAbsent(key, mappingFunction)));
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return Versioned.valueOrNull(complete(backingMap.computeIfPresent(key, remappingFunction)));
    }

    @Override
    public Set<K> keySet() {
        return complete(backingMap.keySet());
    }

    @Override
    public Collection<V> values() {
        return Collections2.transform(complete(backingMap.values()), v -> v.value());
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return complete(backingMap.entrySet())
                         .stream()
                         .map(entry -> Maps.immutableEntry(entry.getKey(), entry.getValue().value()))
                         .collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        // Map like output
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Iterator<Entry<K, Versioned<V>>> it = complete(backingMap.entrySet()).iterator();
        while (it.hasNext()) {
            Entry<K, Versioned<V>> entry = it.next();
            sb.append(entry.getKey()).append('=').append(entry.getValue().value());
            if (it.hasNext()) {
                sb.append(',').append(' ');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        entrySet().forEach(e -> action.accept(e.getKey(), e.getValue()));
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return computeIfPresent(key, (k, v) -> v == null ? value : remappingFunction.apply(v, value));
    }

    private <T> T complete(CompletableFuture<T> future) {
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsistentMapException.Interrupted();
        } catch (TimeoutException e) {
            throw new ConsistentMapException.Timeout();
        } catch (ExecutionException e) {
            Throwables.propagateIfPossible(e.getCause());
            throw new ConsistentMapException(e.getCause());
        }
    }
}