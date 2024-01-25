package io.pravega.storage.obs;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import io.pravega.common.io.StreamHelpers;
import io.pravega.segmentstore.storage.chunklayer.*;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.http.HttpStatus;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import com.obs.services.model.*;

public class OBSChunkStorage extends BaseChunkStorage {
    public static final String NO_SUCH_KEY = "NoSuchKey";
    public static final String PRECONDITION_FAILED = "PreconditionFailed";
    public static final String INVALID_RANGE = "InvalidRange";
    public static final String INVALID_ARGUMENT = "InvalidArgument";
    public static final String METHOD_NOT_ALLOWED = "MethodNotAllowed";
    public static final String ACCESS_DENIED = "AccessDenied";
    public static final String INVALID_PART = "InvalidPart";

    //region members
    private final OBSStorageConfig config;
    private final ObsClient client;
    private final boolean shouldCloseClient;
    private final AtomicBoolean closed;

    //endregion

    //region constructor
    public OBSChunkStorage(ObsClient client, OBSStorageConfig config, Executor executor, boolean shouldCloseClient) {
        super(executor);
        this.config = Preconditions.checkNotNull(config, "config");
        this.client = Preconditions.checkNotNull(client, "client");
        this.closed = new AtomicBoolean(false);
        this.shouldCloseClient = shouldCloseClient;
    }
    //endregion

    //region capabilities

    @Override
    public boolean supportsConcat() {
        return true;
    }

    @Override
    public boolean supportsAppend() {
        return false;
    }

    @Override
    public boolean supportsTruncation() {
        return false;
    }

    //endregion

    //region implementation

    @Override
    protected ChunkHandle doOpenRead(String chunkName) throws ChunkStorageException {
        if (!checkExists(chunkName)) {
            throw new ChunkNotFoundException(chunkName, "doOpenRead");
        }
        return ChunkHandle.readHandle(chunkName);
    }

    @Override
    protected ChunkHandle doOpenWrite(String chunkName) throws ChunkStorageException {
        if (!checkExists(chunkName)) {
            throw new ChunkNotFoundException(chunkName, "doOpenWrite");
        }

        return new ChunkHandle(chunkName, false);
    }

    @Override
    protected int doRead(ChunkHandle handle, long fromOffset, int length, byte[] buffer, int bufferOffset) throws ChunkStorageException {
        try {
            GetObjectRequest objectRequest = new  GetObjectRequest(config.getBucket(),getObjectPath(handle.getChunkName()));
            objectRequest.setRangeStart(fromOffset);
            objectRequest.setRangeEnd(fromOffset+length-1);
            ObsObject obsObject = client.getObject(objectRequest);
            try (val inputStream = obsObject.getObjectContent()) {
                return StreamHelpers.readAll(inputStream, buffer, bufferOffset, length);
            }
        } catch (Exception e) {
            throw convertException(handle.getChunkName(), "doRead", e);
        }
    }

    @Override
    protected int doWrite(ChunkHandle handle, long offset, int length, InputStream data) {
        throw new UnsupportedOperationException("S3ChunkStorage does not support writing to already existing objects.");
    }

    @Override
    public int doConcat(ConcatArgument[] chunks) throws ChunkStorageException {
        int totalBytesConcatenated = 0;
        String targetPath = getObjectPath(chunks[0].getName());
        String uploadId = null;
        boolean isCompleted = false;
        try {
            int partNumber = 1;
            InitiateMultipartUploadRequest initiateMultipartUploadRequest = new InitiateMultipartUploadRequest(config.getBucket(),targetPath);
            ObjectMetadata metadata = new ObjectMetadata(); //并没有设置metadata的Key和value,即默认设置
            initiateMultipartUploadRequest.setMetadata(metadata);
            InitiateMultipartUploadResult result = client.initiateMultipartUpload(initiateMultipartUploadRequest);
            uploadId = result.getUploadId();

            // check whether the target exists
            if (!checkExists(chunks[0].getName())) {
                throw new ChunkNotFoundException(chunks[0].getName(), "doConcat - Target segment does not exist");
            }
            //CompletedPart[] completedParts = new CompletedPart[chunks.length];

            List<PartEtag> completedParts = new ArrayList<>(chunks.length);
            //Copy the parts
            for (int i = 0; i < chunks.length; i++) {
                if (0 != chunks[i].getLength()) {
                    val sourceHandle = chunks[i];
                    long objectSize = client.getObjectMetadata(config.getBucket(),targetPath).getContentLength();

                    Preconditions.checkState(objectSize >= chunks[i].getLength(),
                            "Length of object should be equal or greater. Length on LTS={} provided={}",
                            objectSize, chunks[i].getLength());
                    final long rangeStart = 0;
                    final long rangeEnd = chunks[i].getLength()-1;
                    CopyPartRequest copyPartRequest = new CopyPartRequest();
                    copyPartRequest.setUploadId(uploadId);
                    copyPartRequest.setSourceBucketName(config.getBucket());
                    copyPartRequest.setSourceObjectKey(getObjectPath(sourceHandle.getName()));
                    copyPartRequest.setDestinationBucketName(config.getBucket());
                    copyPartRequest.setDestinationObjectKey(targetPath);
                    copyPartRequest.setByteRangeStart(rangeStart);
                    copyPartRequest.setByteRangeEnd(rangeEnd);
                    copyPartRequest.setPartNumber(partNumber);
                    CopyPartResult copyPartResult;
                    try
                    {
                        copyPartResult = client.copyPart(copyPartRequest);
                        completedParts.add(new PartEtag(copyPartResult.getEtag(),copyPartResult.getPartNumber()));
                    }
                    catch (ObsException e)
                    {
                        e.printStackTrace();
                    }
                    partNumber++;
                    totalBytesConcatenated += chunks[i].getLength();
                }
            }

            //Close the upload
            CompleteMultipartUploadRequest completeMultipartUploadRequest = new CompleteMultipartUploadRequest(config.getBucket(), targetPath, uploadId, completedParts);
            client.completeMultipartUpload(completeMultipartUploadRequest);
            isCompleted = true;
        } catch (RuntimeException e) {
            // Make spotbugs happy. Wants us to catch RuntimeException in a separate catch block.
            // Error message is REC_CATCH_EXCEPTION: Exception is caught when Exception is not thrown
            throw convertException(chunks[0].getName(), "doConcat", e);
        } catch (Exception e) {
            throw convertException(chunks[0].getName(), "doConcat", e);
        } finally {
            if (!isCompleted && null != uploadId) {
                try {
                    AbortMultipartUploadRequest abortMultipartUploadRequest = new AbortMultipartUploadRequest();
                    abortMultipartUploadRequest.setBucketName(config.getBucket());
                    abortMultipartUploadRequest.setObjectKey(targetPath);
                    abortMultipartUploadRequest.setUploadId(uploadId);
                    client.abortMultipartUpload(abortMultipartUploadRequest);
                } catch (Exception e) {
                    throw convertException(chunks[0].getName(), "doConcat", e);
                }
            }
        }
        return totalBytesConcatenated;
    }

    @Override
    protected void doSetReadOnly(ChunkHandle handle, boolean isReadOnly) throws ChunkStorageException {
        try {
            setPermission(handle, isReadOnly ? Permission.PERMISSION_READ : Permission.PERMISSION_FULL_CONTROL);
        } catch (Exception e) {
            throw convertException(handle.getChunkName(), "doSetReadOnly", e);
        }
    }

    private void setPermission(ChunkHandle handle, Permission permission) {
        AccessControlList acl = new AccessControlList();
        acl.grantPermission(GroupGrantee.ALL_USERS,permission);
        client.setBucketAcl(config.getBucket(),acl);
    }

    @Override
    protected ChunkInfo doGetInfo(String chunkName) throws ChunkStorageException {
        try {
            val objectPath = getObjectPath(chunkName);
            long objectSize = client.getObjectMetadata(config.getBucket(),objectPath).getContentLength();

            return ChunkInfo.builder()
                    .name(chunkName)
                    .length(objectSize)
                    .build();
        } catch (Exception e) {
            throw convertException(chunkName, "doGetInfo", e);
        }
    }

    @Override
    protected ChunkHandle doCreate(String chunkName) {
        throw new UnsupportedOperationException("OBSChunkStorage does not support creating object without content.");
    }


    @Override
    @SneakyThrows
    public void close() {
        if (shouldCloseClient && !this.closed.getAndSet(true)) {
            this.client.close();
        }
        super.close();
    }
    @Override
    protected boolean checkExists(String chunkName) throws ChunkStorageException {
        boolean flag=false;
        try {
            flag=client.doesObjectExist(this.config.getBucket(), chunkName);
        }
        catch (Exception e) {
            System.out.println("Error occurred while checking the chunk existence");
            throw convertException(chunkName, "checkExists", e);
        }
        if (flag) {
            System.out.println("exists!");
        } else {
            System.out.println("does not exist!");
        }
        return flag;
    }

    @Override
    protected void doDelete(ChunkHandle handle) throws ChunkStorageException{
        try {
            // 创建删除对象请求对象
            DeleteObjectRequest request = new DeleteObjectRequest(this.config.getBucket(), handle.getChunkName());
            // 发送删除请求并获取响应结果
            DeleteObjectResult result = client.deleteObject(request);
            // 打印响应结果
            System.out.println("Delete result: " + result);
        } catch (Exception e) {
            System.out.println("Failed to delete object from bucket, error message: " + e.getMessage());
            throw convertException(handle.getChunkName(), "doDelete", e);

        }
    }
    @Override
    protected ChunkHandle doCreateWithContent(String chunkName, int length, InputStream data) throws ChunkStorageException {
        try {

            Map<String, String> metadata = new HashMap<>();
            metadata.put("Content-Type", "application/octet-stream");
            metadata.put("Content-Length", Integer.toString(length));
            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType("Content-Type");
            objectMetadata.setContentLength(Long.parseLong(metadata.get("Content-Length")));
            PutObjectRequest request=new PutObjectRequest(this.config.getBucket(),chunkName,data);
            request.setMetadata(objectMetadata);
            PutObjectResult result = client.putObject(request);
            return ChunkHandle.writeHandle(chunkName);
        } catch (Exception e) {
            throw convertException(chunkName, "doCreateWithContent", e);
        }
    }

    private ChunkStorageException convertException(String chunkName, String message, Exception e) {
        ChunkStorageException retValue = null;
        if (e instanceof ChunkStorageException) {
            return (ChunkStorageException) e;
        }
        if (e instanceof ObsException) {
            ObsException obsException = (ObsException) e;
            String errorCode = Strings.nullToEmpty(obsException.getErrorCode());

            if (errorCode.equals(NO_SUCH_KEY)) {
                retValue = new ChunkNotFoundException(chunkName, message, e);
            }

            if (errorCode.equals(PRECONDITION_FAILED)) {
                retValue = new ChunkAlreadyExistsException(chunkName, message, e);
            }

            if (errorCode.equals(INVALID_RANGE)
                    || errorCode.equals(INVALID_ARGUMENT)
                    || errorCode.equals(METHOD_NOT_ALLOWED)
                    || ((ObsException) e).getResponseCode()== HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE) {
                throw new IllegalArgumentException(chunkName, e);
            }

            if (errorCode.equals(ACCESS_DENIED)) {
                retValue = new ChunkStorageException(chunkName, String.format("Access denied for chunk %s - %s.", chunkName, message), e);
            }
        }

        if (retValue == null) {
            retValue = new ChunkStorageException(chunkName, message, e);
        }

        return retValue;
    }

    private String getObjectPath(String objectName) {
        return config.getPrefix() + objectName;
    }

    //endregion
}
