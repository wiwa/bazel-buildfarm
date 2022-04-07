package persistent;

public interface ObjectPool<K, V> {

  V obtain(K key);

  K release(V obj);
}
