package cd.hrpay.notify;

import cd.hrpay.security.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Announcements: everyone reads those addressed to them; HR writes and sends them. */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final Announcements announcements;

    public AnnouncementController(Announcements announcements) {
        this.announcements = announcements;
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public List<Announcements.Announcement> mine() {
        return announcements.mine(CurrentUser.id());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Announcements.Announcement one(@PathVariable long id) {
        return announcements.one(id, CurrentUser.id(), CurrentUser.has("announcement.send"));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('announcement.send')")
    public List<Announcements.Summary> all() {
        return announcements.all();
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('announcement.send')")
    public Announcements.Preview preview(@RequestBody Announcements.Input in) {
        return announcements.preview(in);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('announcement.send')")
    public Announcements.Summary send(@RequestBody Announcements.Input in) {
        return announcements.send(in, CurrentUser.email());
    }
}
