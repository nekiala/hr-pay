package cd.hrpay.holiday;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "holiday_definition")
public class HolidayDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String countryCode;
    private String name;
    private int usualMonth;
    private int usualDay;
    private boolean paid = true;
    private boolean active = true;

    protected HolidayDefinition() {}

    public HolidayDefinition(String countryCode) {
        this.countryCode = countryCode;
    }

    public Long getId() { return id; }
    public String getCountryCode() { return countryCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getUsualMonth() { return usualMonth; }
    public void setUsualMonth(int usualMonth) { this.usualMonth = usualMonth; }
    public int getUsualDay() { return usualDay; }
    public void setUsualDay(int usualDay) { this.usualDay = usualDay; }
    public boolean isPaid() { return paid; }
    public void setPaid(boolean paid) { this.paid = paid; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
