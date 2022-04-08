package persistent.pool;


import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;

public class CommonsPool<K, V> extends GenericKeyedObjectPool<K, V> {
    public CommonsPool(KeyedPooledObjectFactory<K, V> factory) {
        super(factory);
    }
}
