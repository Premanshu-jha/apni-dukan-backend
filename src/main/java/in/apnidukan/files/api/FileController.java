package in.apnidukan.files.api;

import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.service.FileLink;
import in.apnidukan.files.service.FileService;
import in.apnidukan.files.service.FileUploadResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    /** Upload a photo or PDF. Returns 201 for a new file, 200 if this shop already had the exact same file. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> upload(@RequestPart("file") MultipartFile file,
                                               @RequestParam("purpose") FilePurpose purpose) {
        FileUploadResult result = files.upload(file, purpose);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(FileResponse.from(result));
    }

    /** A 5-minute link that opens the file directly from R2. {@code download=true} makes the browser save it. */
    @GetMapping("/{id}/link")
    public FileLink link(@PathVariable UUID id,
                         @RequestParam(defaultValue = "false") boolean download) {
        return files.link(id, download);
    }

    /** Deletes the file from Neon and R2. 409 FILE_IN_USE if it's attached to a bill, payment or shop. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        files.delete(id);
        return ResponseEntity.noContent().build();
    }
}
