package io.pravega.storage.obs;

import com.google.common.base.Preconditions;
import io.pravega.segmentstore.storage.*;
import io.pravega.segmentstore.storage.chunklayer.ChunkedSegmentStorageConfig;

import java.util.concurrent.ScheduledExecutorService;

public class OBSStorageFactoryCreator implements StorageFactoryCreator {
    @Override
    public StorageFactory createFactory(StorageFactoryInfo storageFactoryInfo, ConfigSetup setup, ScheduledExecutorService executor) {
        Preconditions.checkNotNull(storageFactoryInfo, "storageFactoryInfo");
        Preconditions.checkNotNull(setup, "setup");
        Preconditions.checkNotNull(executor, "executor");
        Preconditions.checkArgument(storageFactoryInfo.getName().equals("OBS"));
        if (storageFactoryInfo.getStorageLayoutType().equals(StorageLayoutType.CHUNKED_STORAGE)) {
            return new OBSSimpleStorageFactory(setup.getConfig(ChunkedSegmentStorageConfig::builder),
                    setup.getConfig(OBSStorageConfig::builder),
                    executor);
        } else {
            throw new UnsupportedOperationException("S3StorageFactoryCreator only supports CHUNKED_STORAGE.");
        }
    }

    @Override
    public StorageFactoryInfo[] getStorageFactories() {
        return new StorageFactoryInfo[]{
                StorageFactoryInfo.builder()
                        .name("OBS")
                        .storageLayoutType(StorageLayoutType.CHUNKED_STORAGE)
                        .build()
        };
    }
}
