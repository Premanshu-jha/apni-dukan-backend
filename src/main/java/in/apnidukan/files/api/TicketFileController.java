package in.apnidukan.files.api;

import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.service.TicketFileService;
import in.apnidukan.files.service.VideoUploadService.UploadLink;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Photos, videos and PDFs to send with a support ticket (see TicketFileService), by anyone signed in. What comes back
 * is sent with the message as its id; a file never sent is deleted after a day.
 */
@RestController
@RequestMapping("/api/v1/files/ticket")
public class TicketFileController {

    /** What a ticket file is, as the apps need it before sending. */
    public record TicketFile(UUID id, String contentType, long sizeBytes, String name) {
        static TicketFile of(StoredFile f) {
            return new TicketFile(f.getId(), f.getContentType(), f.getSizeBytes(), f.getOriginalName());
        }
    }

    /** @param sizeBytes the video's exact size (at most 100 MB); @param contentType video/mp4, video/quicktime or video/webm */
    public record StartVideo(@NotNull Long sizeBytes, @NotBlank @Size(max = 100) String contentType) {
    }

    private final TicketFileService files;

    public TicketFileController(TicketFileService files) {
        this.files = files;
    }

    /** A photo (JPEG or PNG, stored cleaned) or a PDF, at most 10 MB. 201. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketFile> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TicketFile.of(files.upload(file)));
    }

    /** 201 with the one-time link to PUT the video to (with exactly this Content-Type), valid 15 minutes. */
    @PostMapping("/videos")
    public ResponseEntity<UploadLink> startVideo(@Valid @RequestBody StartVideo body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(files.startVideo(body.sizeBytes(), body.contentType()));
    }

    /** After the PUT: the video, checked and recorded (the same answer when called again). */
    @PostMapping("/videos/{id}/complete")
    public TicketFile completeVideo(@PathVariable UUID id) {
        return TicketFile.of(files.completeVideo(id));
    }
}
