package org.openchs.web;

import org.openchs.domain.User;
import org.openchs.framework.security.UserContextHolder;
import org.openchs.service.S3Service;
import org.openchs.util.AvniFiles;
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

import java.awt.*;
import java.io.File;
import java.net.URL;
import java.util.UUID;

import static java.lang.String.format;

@RestController
public class MediaController {
    private final Logger logger;
    private final S3Service s3Service;

    @Autowired
    public MediaController(S3Service s3Service) {
        this.s3Service = s3Service;
        logger = LoggerFactory.getLogger(this.getClass());
    }

    @RequestMapping(value = "/media/uploadUrl/{fileName:.+}", method = RequestMethod.GET)
    @PreAuthorize(value = "hasAnyAuthority('user', 'organisation_admin')")
    public ResponseEntity<String> generateUploadUrl(@PathVariable String fileName) {
        try {
            logger.info("getting media upload url");
            URL url = s3Service.generateMediaUploadUrl(fileName);
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

    @PostMapping("/media/saveIcon")
    @PreAuthorize(value = "hasAnyAuthority('organisation_admin', 'admin')")
    public ResponseEntity<?> saveIcon(@RequestParam MultipartFile file) {
        String uuid = UUID.randomUUID().toString();
        User user = UserContextHolder.getUserContext().getUser();
        String targetFileName = format("%s-%s", uuid, file.getOriginalFilename());
        File tempSourceFile;
        try {
            tempSourceFile = AvniFiles.convertMultiPartToFile(file, "");
            AvniFiles.ImageType imageType = AvniFiles.guessImageTypeFromStream(tempSourceFile);
            if(AvniFiles.ImageType.Unknown.equals(imageType)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Unsupported file. Use .bmp, .jpg, .jpeg, .png, .gif file.");
            }
            Dimension dimension = AvniFiles.getImageDimension(tempSourceFile, imageType);
            if(dimension.getHeight() > 75 || dimension.getWidth() > 75) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Unsupported file. Use image of size 75 X 75 or smaller.");
            }
            String s3Filekey = s3Service.uploadImageFile(tempSourceFile, targetFileName, "icons");
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(s3Filekey);
        } catch (Exception e) {
            logger.error(format("Icon upload failed. file:'%s', user:'%s'", file.getOriginalFilename(), user.getUsername()));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(format("Unable to save Icon. %s", e.getMessage()));
        }
    }
}
