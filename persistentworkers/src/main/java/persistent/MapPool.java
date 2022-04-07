package persistent;

import java.util.HashMap;
import java.util.function.Function;

public class MapPool<K, V> implements ObjectPool<K, V> {

  private final HashMap<K, V> map;

  private final Function<K, V> objFactory;

  private final Function<V, K> identifier;

  public MapPool(Function<K, V> factory, Function<V, K> identifier) {
    this.map = new HashMap<>();
    this.objFactory = factory;
    this.identifier = identifier;
  }

  @Override
  public V obtain(K key) {
    if (map.containsKey(key)) {
      return map.remove(key);
    }
    return objFactory.apply(key);
  }

  @Override
  public K release(V obj) {
    K key = identifier.apply(obj);
    map.put(key, obj);
    return key;
  }
}
