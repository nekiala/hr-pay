package cd.hrpay.holiday;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One year's instance of a holiday. Attendance and payroll use only the observed date. */
@Entity
@Table(name = "holiday_occurrence")
public class HolidayOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "definition_id")
    private HolidayDefinition definition;
    private int year;
    private LocalDate officialDate;
    private LocalDate observedDate;
    private String announcementRef;
    private String setBy;
    private OffsetDateTime setAt;

    protected HolidayOccurrence() {}

    public HolidayOccurrence(HolidayDefinition definition, int year, LocalDate officialDate) {
        this.definition = definition;
        this.year = year;
        this.officialDate = officialDate;
        this.observedDate = officialDate;
    }

    public Long getId() { return id; }
    public HolidayDefinition getDefinition() { return definition; }
    public int getYear() { return year; }
    public LocalDate getOfficialDate() { return officialDate; }
    public LocalDate getObservedDate() { return observedDate; }
    public String getAnnouncementRef() { return announcementRef; }
    public String getSetBy() { return setBy; }
    public OffsetDateTime getSetAt() { return setAt; }

    public void moveTo(LocalDate observed, String announcement, String by) {
        this.observedDate = observed;
        this.announcementRef = announcement;
        this.setBy = by;
        this.setAt = OffsetDateTime.now();
    }
}
