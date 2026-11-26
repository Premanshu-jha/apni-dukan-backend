package in.apnidukan.leads.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.leads.service.LeadService;
import in.apnidukan.leads.service.LeadService.AreaStatusView;
import in.apnidukan.leads.service.LeadService.AreasView;
import in.apnidukan.leads.service.LeadService.ChangesRequest;
import in.apnidukan.leads.service.LeadService.ChangesView;
import in.apnidukan.leads.service.LeadService.DistrictView;
import in.apnidukan.leads.service.LeadService.DueView;
import in.apnidukan.leads.service.LeadService.EventView;
import in.apnidukan.leads.service.LeadService.FunnelView;
import in.apnidukan.leads.service.LeadService.LeadView;
import in.apnidukan.leads.service.LeadService.RefreshRequest;
import in.apnidukan.leads.service.LeadService.SaveLead;
import in.apnidukan.leads.service.LeadService.ScanView;
import in.apnidukan.leads.service.LeadService.UsageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin website's Leads page (field agents and super admins; 404 for anyone else). See {@link LeadService} for
 * what each answers and who may change what.
 */
@RestController
@RequestMapping("/api/v1/admin/leads")
public class AdminLeadsController {

    private final LeadService leads;
    private final CurrentUserProvider currentUser;

    public AdminLeadsController(LeadService leads, CurrentUserProvider currentUser) {
        this.leads = leads;
        this.currentUser = currentUser;
    }

    /**
     * Cities and districts for what was typed: {@code /districts?q=kuk&cities=true}, {@code ?q=500087&cities=true}.
     * Free (our own list). Without {@code cities=true}: districts only, as the admin website before cities expects.
     */
    @GetMapping("/districts")
    public List<DistrictView> districts(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "false") boolean cities) {
        return leads.districts(caller(), q, cities);
    }

    /** What people call a place, looked up on Mapbox: {@code /places?q=yapral}. One map request each. */
    @GetMapping("/places")
    public List<DistrictView> places(@RequestParam(defaultValue = "") String q) {
        return leads.places(caller(), q);
    }

    /** A city's or district's areas: {@code /areas?kind=CITY&district=Hyderabad&state=Telangana}. */
    @GetMapping("/areas")
    public AreasView areas(@RequestParam(required = false) String kind,
                           @RequestParam(required = false) String district,
                           @RequestParam(required = false) String state) {
        return leads.areas(caller(), kind, district, state);
    }

    /** Searches one area live on the map. A POST: each scan makes paid Mapbox requests (never prefetched or cached). */
    @PostMapping("/scan/{pincode}")
    public ScanView scan(@PathVariable String pincode) {
        return leads.scan(caller(), pincode);
    }

    /**
     * Which of the page's searched areas changed since it last brought each up to date (no map requests): the page
     * asks every 30 seconds while the Leads page is on screen.
     */
    @PostMapping("/changes")
    public ChangesView changes(@RequestBody(required = false) ChangesRequest body) {
        return leads.changes(caller(), body);
    }

    /** One searched area's leads and Clozest salons as they are now, for the businesses the page shows (no map requests). */
    @PostMapping("/refresh/{pincode}")
    public AreaStatusView refresh(@PathVariable String pincode, @RequestBody(required = false) RefreshRequest body) {
        return leads.refresh(caller(), pincode, body);
    }

    /** Saves the team's work on a lead (Mapbox's id for the business), e.g. status, note, follow-up. */
    @PutMapping("/{mapboxId}")
    public LeadView save(@PathVariable String mapboxId, @RequestBody(required = false) SaveLead body) {
        return leads.save(caller(), mapboxId, body);
    }

    @GetMapping("/{mapboxId}/history")
    public List<EventView> history(@PathVariable String mapboxId) {
        return leads.history(caller(), mapboxId);
    }

    /** Follow-ups due today or earlier: the caller's, or everyone's for a super admin ({@code ?everyone=true}). */
    @GetMapping("/due")
    public List<DueView> due(@RequestParam(defaultValue = "false") boolean everyone) {
        return leads.due(caller(), everyone);
    }

    /** My leads, from their saved details (no map search): the caller's, or everyone's for a super admin. */
    @GetMapping("/mine")
    public List<DueView> mine(@RequestParam(defaultValue = "false") boolean everyone) {
        return leads.mine(caller(), everyone);
    }

    /** A city's or district's funnel (super admins). */
    @GetMapping("/funnel")
    public FunnelView funnel(@RequestParam(required = false) String kind,
                             @RequestParam(required = false) String district,
                             @RequestParam(required = false) String state) {
        return leads.funnel(caller(), kind, district, state);
    }

    @GetMapping("/usage")
    public UsageView usage() {
        return leads.usage(caller());
    }

    private UUID caller() {
        return currentUser.get().userId();
    }
}
