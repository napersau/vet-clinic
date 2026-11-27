package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.service.FileUrlSigner;
import com.vetclinic.clinical.service.ImagingStorage;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Phục vụ tệp ảnh bằng URL ký tạm (Quy chuẩn 7.5): {@code GET /files/imaging/yyyy/MM/<uuid>.<đuôi>?exp=..&sig=..}. Endpoint công khai ở Gateway và
 * ở service (thẻ img không gắn được Authorization); quyền xem được kiểm tra lúc cấp URL, ở đây chỉ kiểm tra chữ ký và hạn TRƯỚC khi chạm vào đĩa.
 * file_key phải đúng khuôn của {@link ImagingStorage} nên không có path traversal.
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    private static final Pattern FILE_KEY = Pattern.compile("imaging/\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|dcm)");

    private final FileUrlSigner signer;
    private final ImagingStorage storage;

    @GetMapping("/files/**")
    public ResponseEntity<Resource> file(HttpServletRequest request,
                                         @RequestParam(required = false) Long exp,
                                         @RequestParam(required = false) String sig) {
        String path = request.getRequestURI();
        String fileKey = path.substring(path.indexOf(FileUrlSigner.PREFIX) + FileUrlSigner.PREFIX.length());
        if (!FILE_KEY.matcher(fileKey).matches()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        signer.verify(fileKey, exp, sig);
        boolean dicom = fileKey.endsWith(".dcm");
        String contentType = fileKey.endsWith(".png") ? MediaType.IMAGE_PNG_VALUE : dicom ? "application/dicom" : MediaType.IMAGE_JPEG_VALUE;
        ContentDisposition disposition = (dicom ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(fileKey.substring(fileKey.lastIndexOf('/') + 1)).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(storage.load(fileKey));
    }
}
