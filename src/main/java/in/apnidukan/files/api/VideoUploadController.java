package in.apnidukan.files.api;

import in.apnidukan.files.service.FileUploadResult;
import in.apnidukan.files.service.VideoUploadService;
import in.apnidukan.files.service.VideoUploadService.UploadLink;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The salon video: a one-time link the phone uploads to directly, then "done" (see VideoUploadService). */
@RestController
@RequestMapping("/api/v1/files/video-uploads")
public class VideoUploadController {

    /**
     * @param sizeBytes       the file's exact size (at most 100 MB)
     * @param contentType     video/mp4, video/quicktime or video/webm
     * @param durationSeconds its length as the phone read it (15-45); null when the phone couldn't read it
     */
    public record Start(@NotNull Long sizeBytes, @NotBlank @Size(max = 100) String contentType,
                        Integer durationSeconds) {
    }

    private final VideoUploadService videos;

    public VideoUploadController(VideoUploadService videos) {
        this.videos = videos;
    }

    /** 201 with the link to PUT the file to (with exactly this Content-Type), valid 15 minutes. A salon's owner only. */
    @PostMapping
    public ResponseEntity<UploadLink> start(@Valid @RequestBody Start body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(videos.start(body.sizeBytes(), body.contentType(), body.durationSeconds()));
    }

    /** After the PUT: 201 with the recorded file (200 if it was already completed). Then PUT /shops/me/video. */
    @PostMapping("/{id}/complete")
    public ResponseEntity<FileResponse> complete(@PathVariable UUID id) {
        FileUploadResult result = videos.complete(id);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(FileResponse.from(result));
    }
}
