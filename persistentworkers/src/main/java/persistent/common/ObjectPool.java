package persistent.common;

public interface ObjectPool<K, V> {

  V obtain(K key);

  K release(V obj);
}
