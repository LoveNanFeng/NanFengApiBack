package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final long MAX_IMAGE_SIZE = 2 * 1024 * 1024;
    private static final long MAX_NOTICE_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Path INTERFACE_AVATAR_DIR = Path.of("uploads", "interface-avatar").toAbsolutePath().normalize();
    private static final Path NOTICE_IMAGE_DIR = Path.of("uploads", "notice-image").toAbsolutePath().normalize();
    private static final Path SITE_LOGO_DIR = Path.of("uploads", "site-logo").toAbsolutePath().normalize();

    @PostMapping("/interface-avatar")
    public ApiResponse<Map<String, String>> uploadInterfaceAvatar(@RequestParam("file") MultipartFile file) {
        assertAdmin("只有管理员可以上传接口图片");
        return saveImage(file, INTERFACE_AVATAR_DIR, "/api/upload/interface-avatar/", MAX_IMAGE_SIZE);
    }

    @PostMapping("/notice-image")
    public ApiResponse<Map<String, String>> uploadNoticeImage(@RequestParam("file") MultipartFile file) {
        assertAdmin("只有管理员可以上传公告图片");
        return saveImage(file, NOTICE_IMAGE_DIR, "/api/upload/notice-image/", MAX_NOTICE_IMAGE_SIZE);
    }

    @PostMapping("/site-logo")
    public ApiResponse<Map<String, String>> uploadSiteLogo(@RequestParam("file") MultipartFile file) {
        assertAdmin("只有管理员可以上传网站 Logo");
        return saveImage(file, SITE_LOGO_DIR, "/api/upload/site-logo/", MAX_IMAGE_SIZE);
    }

    private ApiResponse<Map<String, String>> saveImage(
        MultipartFile file,
        Path uploadDir,
        String urlPrefix,
        long maxSize
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "上传图片不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "图片大小不能超过" + (maxSize / 1024 / 1024) + "MB");
        }

        String extension = extension(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension) || !contentType.startsWith("image/")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "仅支持 JPG、PNG、WEBP、GIF 图片");
        }

        try {
            Files.createDirectories(uploadDir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            Path target = uploadDir.resolve(fileName).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "文件名不合法");
            }
            file.transferTo(target);
            return ApiResponse.ok(Map.of("url", urlPrefix + fileName));
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "图片上传失败，请稍后重试");
        }
    }

    private String extension(String originalFilename) {
        String filename = StringUtils.cleanPath(originalFilename == null ? "" : originalFilename);
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase();
    }

    private void assertAdmin(String message) {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, message);
        }
    }
}
