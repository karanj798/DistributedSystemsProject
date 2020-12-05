package server;

import common.CommonService;
import common.FileUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisStringCommands;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.rmi.RemoteException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RemoteObj implements CommonService {
    PublisherReplica pReplica;
    SubscriberReplica sReplica;
    FileUtils fileUtils;
    String nodeType;

    public RemoteObj (String pubAddr, String subAddr, String nodeType){
        this.nodeType = nodeType;
        this.fileUtils = new FileUtils();
        if(pubAddr != null){
            this.pReplica = new PublisherReplica(pubAddr, nodeType);
            pReplica.start();
        }

        if(subAddr != null){
            this.sReplica = new SubscriberReplica(subAddr, nodeType);
            sReplica.start();
        }
    }

    @Override
    public String getMessage() throws RemoteException {
        return "Hello Client!";
    }

    @Override
    public void uploadFile(String fileName, byte[] fileContent) throws RemoteException {
        try {
            String directoryPath = fileUtils.getResourcesPath() + this.nodeType;
            File directory = new File(directoryPath);
            if (! directory.exists()) directory.mkdir(); //Create a directory if it does not exist

            File file = new File(directoryPath + "/" + fileName);
            BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file.getAbsoluteFile()));
            output.write(fileContent, 0, fileContent.length);
            output.flush();
            output.close();

            System.out.println("Successfully received the file " + fileName + " from the Client to sign...");

            pReplica.publishNewFile(fileName, fileContent);
        } catch (Exception e) {
            System.out.println("RemoteObj: " + e.getMessage());
        }
    }

    @Override
    public void insertKey(String userName, String publicKey) throws RemoteException {
        RedisURI redisURI = RedisURI.Builder.redis("127.0.0.1", 6379).build();
        RedisClient redisClient = RedisClient.create(redisURI);
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisStringCommands<String, String> sync = connection.sync();
        RedisAsyncCommands<String, String> async = connection.async();

        if (sync.get(userName) == null) {
            async.set(userName, publicKey);
        }

        connection.close();
        redisClient.shutdown();
    }

    @Override
    public void signDocument(String fileName) throws RemoteException{
        String directoryPath = fileUtils.getResourcesPath() + this.nodeType;
        PDFHandler pdfHandler = new PDFHandler(new File(directoryPath + "/" + fileName));
        pdfHandler.loadDocument();

        // replace after ->
        List<String> signatures = new ArrayList<>();
        signatures.add("Karan");
        signatures.add("John");

        List<Timestamp> timestamps = new ArrayList<>();

        for (int i = 1; i <= 2; i++) {
            timestamps.add(new Timestamp(System.currentTimeMillis()));
        }
        // <- replace after

        pdfHandler.addSignature(signatures, timestamps);
        File signedFile = pdfHandler.savePDFFile();

        // Replicate on the backup server
        try {
            pReplica.publishNewFile(signedFile.getName(), Files.readAllBytes(signedFile.toPath()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void request(String fileName, byte[] fileContent, String[] signerList) throws RemoteException {
        uploadFile(fileName, fileContent);
        cacheRequest(fileName, signerList);
    }

    public void cacheRequest(String fileName, String[] signerList) {

    }
}