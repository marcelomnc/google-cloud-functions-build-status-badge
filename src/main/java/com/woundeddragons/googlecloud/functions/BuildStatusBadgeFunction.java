package com.woundeddragons.googlecloud.functions;

import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.cloud.storage.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.woundeddragons.googlecloud.functions.dto.PubSubMessageDTO;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

public class BuildStatusBadgeFunction implements BackgroundFunction<PubSubMessageDTO> {
    private static final Logger logger = Logger.getLogger(BuildStatusBadgeFunction.class.getName());

    @Override
    public void accept(PubSubMessageDTO pubSubMessageDTO, Context context) throws RuntimeException {
        if (pubSubMessageDTO != null && pubSubMessageDTO.getData() != null) {
            String repoNameToWatch = Optional.ofNullable(System.getenv("REPO_NAME"))
                    .orElseThrow(() -> new RuntimeException("Environment variable 'REPO_NAME' must be set for this function."));
            //By default we will watch for builds on 'master' branches
            String branchNameToWatch = Optional.ofNullable(System.getenv("BRANCH_NAME")).orElse("master");
            //By default we will use 'build-status-badges' as google cloud storage bucket name
            String storageBucketName = Optional.ofNullable(System.getenv("BUCKET_NAME")).orElse("build-status-badges");
            //By default we will use 'last-build-status-badge.svg' as the name for the badge image of the last build status
            String lastBuildStatusBadgeName = Optional.ofNullable(System.getenv("BADGE_NAME")).orElse("last-build-status-badge");
            String targetFileName = lastBuildStatusBadgeName + ".svg";

            logger.info("Repository name to watch for builds: '" + repoNameToWatch + "'");
            logger.info("Branch name to watch for builds: '" + branchNameToWatch + "'");
            logger.info("Storage bucket name to use: '" + storageBucketName + "'");
            logger.info("Last build status badge name to use: '" + targetFileName + "'");

            byte[] decodedBytes = Base64.getDecoder().decode(pubSubMessageDTO.getData().getBytes(StandardCharsets.UTF_8));
            String decodedData = new String(decodedBytes, StandardCharsets.UTF_8);
            JsonObject messageDataJsonObject = JsonParser.parseString(decodedData).getAsJsonObject();
            String receivedBuildStatus = messageDataJsonObject.get("status").getAsString();
            JsonObject substitutionsJsonObject = messageDataJsonObject.get("substitutions").getAsJsonObject();
            String receivedRepoName = substitutionsJsonObject.get("REPO_NAME").getAsString();
            String receivedBranchName = substitutionsJsonObject.get("BRANCH_NAME").getAsString();
            logger.info("Received repository name: '" + receivedRepoName + "'");
            logger.info("Received branch name: '" + receivedBranchName + "'");
            logger.info("Received build status: '" + receivedBuildStatus + "'");

            if (repoNameToWatch.equals(receivedRepoName)
                    && branchNameToWatch.equals(receivedBranchName)) {
                logger.info("Building badge ...");
                //Specify no-cache to avoid stale build status badges
                String cacheControlMetadataToSet = "no-cache, max-age=0";
                String sourceFileName = receivedBuildStatus.toLowerCase() + ".svg";

                //Copy badge object
                Storage storage = StorageOptions.newBuilder().setProjectId(messageDataJsonObject.get("projectId").getAsString()).build().getService();
                Blob source = Optional.ofNullable(storage.get(storageBucketName, sourceFileName))
                        .orElseThrow(() -> new RuntimeException("Storage object '" + sourceFileName + "' not found in bucket '" + storageBucketName + "'"));
                BlobInfo target = BlobInfo.newBuilder(BlobId.of(storageBucketName, targetFileName))
                        .setContentType(source.getContentType())
                        .setCacheControl(cacheControlMetadataToSet)
                        .build();
                Storage.CopyRequest copyRequest = Storage.CopyRequest.newBuilder()
                        .setSource(source.getBlobId())
                        .setTarget(target)
                        .build();
                storage.copy(copyRequest);
                logger.info("Storage object '" + source.getName() + "' copied as '" + target.getName() + "' in bucket '" + storageBucketName + "'");

                //Make badge object public
                storage.createAcl(target.getBlobId(), Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
                logger.info("Storage object '" + target.getName() + "' made public in bucket '" + storageBucketName + "'");

                logger.info("Badge built.");
            } else {
                logger.info("Received data ignored, no badge built.");
            }
        }
    }
}