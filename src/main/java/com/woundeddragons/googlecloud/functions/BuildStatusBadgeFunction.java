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
            //By default we will watch for builds on 'master' branches
            String defaultBranchNameRegexToWatch = "^master$";
            //By default we will use 'build-status-badges' as the google cloud storage bucket name
            String defaultStorageBucketName = "build-status-badges";
            //By default we will use 'last-build-status-badge.svg' as the name for the badge image of the last build status
            String defaultLastBuildStatusBadgeName = "last-build-status-badge";

            String repoNameRegexToWatch = Optional.ofNullable(System.getenv("REPO_NAME_REGEX"))
                    .orElseThrow(() -> new RuntimeException("Environment variable 'REPO_NAME_REGEX' must be set for this function."));
            String branchNameRegexToWatch = Optional.ofNullable(System.getenv("BRANCH_NAME_REGEX")).orElse(defaultBranchNameRegexToWatch);
            Optional<String> tagNameRegexToWatch = Optional.ofNullable(System.getenv("TAG_NAME_REGEX"));
            String storageBucketName = Optional.ofNullable(System.getenv("BUCKET_NAME")).orElse(defaultStorageBucketName);
            String lastBuildStatusBadgeName = Optional.ofNullable(System.getenv("BADGE_NAME")).orElse(defaultLastBuildStatusBadgeName);
            String targetFileName = lastBuildStatusBadgeName + ".svg";

            String valueNotSet = "[Value Not Set]";
            logger.info("Repository name regex to watch for builds: '" + repoNameRegexToWatch + "'");
            logger.info("Branch name regex to watch for builds: '" + branchNameRegexToWatch + "'");
            logger.info("Tag name regex to watch for builds: '" + tagNameRegexToWatch.orElse(valueNotSet) + "'");
            logger.info("Storage bucket name to use: '" + storageBucketName + "'");
            logger.info("Last build status badge name to use: '" + targetFileName + "'");

            byte[] decodedBytes = Base64.getDecoder().decode(pubSubMessageDTO.getData().getBytes(StandardCharsets.UTF_8));
            String decodedData = new String(decodedBytes, StandardCharsets.UTF_8);
            JsonObject messageDataJsonObject = JsonParser.parseString(decodedData).getAsJsonObject();
            String receivedBuildStatus = messageDataJsonObject.get("status").getAsString();
            JsonObject substitutionsJsonObject = messageDataJsonObject.get("substitutions").getAsJsonObject();
            Optional<String> receivedRepoName = Optional.ofNullable(substitutionsJsonObject.get("REPO_NAME") != null ? substitutionsJsonObject.get("REPO_NAME").getAsString() : null);
            //BRANCH_NAME is not received when the build is triggered by pushing a tag to the repo
            Optional<String> receivedBranchName = Optional.ofNullable(substitutionsJsonObject.get("BRANCH_NAME") != null ? substitutionsJsonObject.get("BRANCH_NAME").getAsString() : null);
            //TAG_NAME is not received when the build is triggered by pushing to a branch
            Optional<String> receivedTagName = Optional.ofNullable(substitutionsJsonObject.get("TAG_NAME") != null ? substitutionsJsonObject.get("TAG_NAME").getAsString() : null);

            logger.info("Received repository name: '" + receivedRepoName.orElse(valueNotSet) + "'");
            logger.info("Received branch name: '" + receivedBranchName.orElse(valueNotSet) + "'");
            logger.info("Received tag name: '" + receivedTagName.orElse(valueNotSet) + "'");
            logger.info("Received build status: '" + receivedBuildStatus + "'");

            boolean doBuildBadgeProcess = false;
            if (receivedRepoName.isPresent() && receivedRepoName.get().matches(repoNameRegexToWatch)) {
                //if TAG_NAME_REGEX is set, it has precedence over the BRANCH_NAME_REGEX check
                if (tagNameRegexToWatch.isPresent()) {
                    if (receivedTagName.isPresent() && receivedTagName.get().matches(tagNameRegexToWatch.get())) {
                        doBuildBadgeProcess = true;
                    }
                } else if (receivedBranchName.isPresent() && receivedBranchName.get().matches(branchNameRegexToWatch)) {
                    doBuildBadgeProcess = true;
                }
            }

            if (doBuildBadgeProcess) {
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