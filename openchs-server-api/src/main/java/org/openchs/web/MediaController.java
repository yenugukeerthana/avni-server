package org.openchs.web;

import com.amazonaws.HttpMethod;
import org.apache.commons.io.FilenameUtils;
import org.openchs.domain.User;
import org.openchs.framework.security.UserContextHolder;
import org.openchs.service.OrganisationConfigService;
import org.openchs.service.S3Service;
import org.openchs.util.AvniFiles;
import org.openchs.web.request.CustomPrintRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.transaction.Transactional;
import javax.validation.Valid;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static java.lang.String.format;

@RestController
public class MediaController {
    private final String CUSTOM_PRINT_DIR = "prints";
    private final Logger logger;
    private final S3Service s3Service;
    private final OrganisationConfigService organisationConfigService;

    @Autowired
    public MediaController(S3Service s3Service, OrganisationConfigService organisationConfigService) {
        this.s3Service = s3Service;
        this.organisationConfigService = organisationConfigService;
        logger = LoggerFactory.getLogger(this.getClass());
    }

    @RequestMapping(value = "/media/uploadUrl/{fileName:.+}", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> generateUploadUrl(@PathVariable String fileName) {
        logger.info("getting media upload url");
        return getFileUrlResponse(fileName, HttpMethod.PUT);
    }

    private ResponseEntity<String> getFileUrlResponse(String fileName, HttpMethod method) {
        try {
            URL url = s3Service.generateMediaUploadUrl(fileName, method);
            logger.debug(format("Generating pre-signed url: %s", url.toString()));
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(url.toString());
        } catch (AccessDeniedException e) {
            logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @RequestMapping(value = "/media/mobileDatabaseBackupUrl/upload", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> generateMobileDatabaseBackupUploadUrl() {
        logger.info("getting mobile database backup upload url");
        return getFileUrlResponse(mobileDatabaseBackupFile(), HttpMethod.PUT);
    }

    private String mobileDatabaseBackupFile() {
        String catchmentUuid = UserContextHolder.getUser().getCatchment().getUuid();
        return String.format("MobileDbBackup-%s", catchmentUuid);
    }

    @RequestMapping(value = "/media/mobileDatabaseBackupUrl/download", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> generateMobileDatabaseBackupDownloadUrl() {
        logger.info("getting mobile database backup download url");
        return getFileUrlResponse(mobileDatabaseBackupFile(), HttpMethod.GET);
    }

    @RequestMapping(value = "/media/mobileDatabaseBackupUrl/exists", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> mobileDatabaseBackupExists() {
        logger.info("checking whether mobile database backup url exists");
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(Boolean.toString(s3Service.fileExists(mobileDatabaseBackupFile())));
    }

    @PostMapping("/media/customPrint/upload")
    @PreAuthorize(value = "hasAnyAuthority('organisation_admin', 'admin')")
    @Transactional
    public ResponseEntity<?> uploadCustomPrint(@RequestPart(value = "file") MultipartFile file,
                                               @RequestPart(value = "printSettings") @Valid CustomPrintRequest printSettings) {
        organisationConfigService.updateSettings(CUSTOM_PRINT_DIR, printSettings.getCustomPrintProperties());
        try {
            Path tempPath = Files.createTempDirectory(UUID.randomUUID().toString()).toFile().toPath();
            AvniFiles.extractFileToPath(file, tempPath);
            s3Service.uploadCustomPrintFile(tempPath.toFile(), CUSTOM_PRINT_DIR);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            logger.error(format("Error while uploading the files %s", e.getMessage()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            logger.error(format("Error while uploading the files %s", e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @RequestMapping(value = "/media/customPrint/download", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> downloadCustomPrintFile(@RequestParam String file) {
        logger.info("getting custom print file download url");
        return getFileUrlResponse(format("%s/%s", CUSTOM_PRINT_DIR, file), HttpMethod.GET);
    }

    @RequestMapping(value = "/media/signedUrl", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> generateDownloadUrl(@RequestParam String url) {
        try {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(s3Service.generateMediaDownloadUrl(url).toString());
        } catch (AccessDeniedException e) {
            logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/web/uploadMedia")
    @PreAuthorize(value = "hasAnyAuthority('organisation_admin', 'admin', 'user')")
    public ResponseEntity<?> uploadMedia(@RequestParam MultipartFile file, @RequestParam String mediaType) {
        String uuid = UUID.randomUUID().toString();
        User user = UserContextHolder.getUserContext().getUser();
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        String targetFilePath = format("%s.%s", uuid, fileExtension);
        try {
            File tempSourceFile = AvniFiles.convertMultiPartToFile(file, "");
            String mimeType = Files.probeContentType(tempSourceFile.toPath());
            if (mimeType == null || !mimeType.toLowerCase().contains(mediaType)) {
                String errorMessage = format("File not supported for upload. Please choose correct %s file.", mediaType);
                logger.error(errorMessage);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
            }
            URL s3FileUrl = s3Service.uploadImageFile(tempSourceFile, targetFilePath);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(s3FileUrl.toString());
        } catch (Exception e) {
            logger.error(format("Media upload failed.  file:'%s', user:'%s'", file.getOriginalFilename(), user.getUsername()));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(format("Unable to upload media. %s", e.getMessage()));
        }
    }

    @PostMapping("/media/saveImage")
    @PreAuthorize(value = "hasAnyAuthority('organisation_admin', 'admin')")
    public ResponseEntity<?> saveImage(@RequestParam MultipartFile file, @RequestParam String bucketName) {
        String uuid = UUID.randomUUID().toString();
        User user = UserContextHolder.getUserContext().getUser();
        File tempSourceFile;
        try {
            tempSourceFile = AvniFiles.convertMultiPartToFile(file, "");
            AvniFiles.ImageType imageType = AvniFiles.guessImageTypeFromStream(tempSourceFile);
            if (AvniFiles.ImageType.Unknown.equals(imageType)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Unsupported file. Use .bmp, .jpg, .jpeg, .png, .gif file.");
            }
            if (bucketName.equals("icons") && isInvalidDimension(tempSourceFile, imageType)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Unsupported file. Use image of size 75 X 75 or smaller.");
            }
            if (bucketName.equals("news") && isInvalidImageSize(file)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Unsupported file. Use image of size 500KB or smaller.");
            }
            String targetFilePath = format("%s/%s%s", bucketName, uuid, imageType.EXT);
            URL s3FileUrl = s3Service.uploadImageFile(tempSourceFile, targetFilePath);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(s3FileUrl.toString());
        } catch (Exception e) {
            logger.error(format("Image upload failed. bucketName: '%s' file:'%s', user:'%s'", bucketName, file.getOriginalFilename(), user.getUsername()));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(format("Unable to save Image. %s", e.getMessage()));
        }
    }

    private boolean isInvalidImageSize(MultipartFile file) {
        return AvniFiles.getSizeInKB(file) > 500;
    }

    private boolean isInvalidDimension(File tempSourceFile, AvniFiles.ImageType imageType) throws IOException {
        Dimension dimension = AvniFiles.getImageDimension(tempSourceFile, imageType);
        return dimension.getHeight() > 75 || dimension.getWidth() > 75;
    }
}
