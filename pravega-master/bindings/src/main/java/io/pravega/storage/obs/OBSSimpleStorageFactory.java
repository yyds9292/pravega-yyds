package io.pravega.storage.obs;

import com.obs.services.ObsClient;
import io.pravega.segmentstore.storage.SimpleStorageFactory;
import io.pravega.segmentstore.storage.Storage;
import io.pravega.segmentstore.storage.chunklayer.ChunkStorage;
import io.pravega.segmentstore.storage.chunklayer.ChunkedSegmentStorage;
import io.pravega.segmentstore.storage.chunklayer.ChunkedSegmentStorageConfig;
import io.pravega.segmentstore.storage.metadata.ChunkMetadataStore;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.concurrent.ScheduledExecutorService;

@RequiredArgsConstructor
public class OBSSimpleStorageFactory implements SimpleStorageFactory {
    private static final String OBS_ACCESS_KEY_ID ="obs.accessKeyId";
    private static final String OBS_SECRET_ACCESS_KEY = "obs.secretAccessKey";
    private static final String OBS_REGION ="obs.region";

    @NotNull
    @Getter
    private final ChunkedSegmentStorageConfig chunkedSegmentStorageConfig;

    @NonNull
    private final OBSStorageConfig config;

    @NonNull
    @Getter
    private final ScheduledExecutorService executor;


    @Override
    public Storage createStorageAdapter(int containerId, ChunkMetadataStore metadataStore) {
        ChunkedSegmentStorage chunkedSegmentStorage = new ChunkedSegmentStorage(containerId,
                createChunkStorage(),
                metadataStore,
                this.executor,
                this.chunkedSegmentStorageConfig);
        return chunkedSegmentStorage;
    }

    @Override
    public ChunkStorage createChunkStorage() {
        ObsClient obsClient = createObsClient(this.config);
        return new OBSChunkStorage(obsClient,this.config,this.executor,true);
    }

    @Override
    public Storage createStorageAdapter() {
        throw new UnsupportedOperationException("SimpleStorageFactory requires ChunkMetadataStore");
    }

    static ObsClient createObsClient(OBSStorageConfig config){//暂时没有写对是否启用IAM Ram用户的判断&重写endpoint地址
        ObsClient obsClient = new ObsClient(config.getAccessKey(), config.getSecretKey(), config.getEndpoint());
        return obsClient;
    }

    private static void setSystemProperties(OBSStorageConfig config) {
        System.setProperty(OBS_ACCESS_KEY_ID, config.getAccessKey());
        System.setProperty(OBS_SECRET_ACCESS_KEY, config.getSecretKey());
        System.setProperty(OBS_REGION, config.getRegion());
    }


}
