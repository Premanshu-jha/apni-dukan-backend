package in.apnidukan.salon.service;

import in.apnidukan.common.files.AttachedFiles;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Barber photos in use can't be deleted from the files module. */
@Component
class SalonFiles implements AttachedFiles {

    private final SalonSetupService setup;

    SalonFiles(SalonSetupService setup) {
        this.setup = setup;
    }

    @Override
    public boolean isAttached(UUID fileId) {
        return setup.usesFile(fileId);
    }
}
