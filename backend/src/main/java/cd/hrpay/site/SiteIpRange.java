package cd.hrpay.site;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "site_ip_range")
public class SiteIpRange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;

    private String cidr;
    private String label;

    protected SiteIpRange() {}

    public SiteIpRange(String cidr, String label) {
        this.cidr = cidr;
        this.label = label;
    }

    void setSite(Site site) { this.site = site; }
    public Long getId() { return id; }
    public String getCidr() { return cidr; }
    public String getLabel() { return label; }
    void setLabel(String label) { this.label = label; }
}
