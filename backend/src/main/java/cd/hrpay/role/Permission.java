package cd.hrpay.role;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permission")
public class Permission {

    @Id
    private String code;
    private String category;
    private String description;

    protected Permission() {}

    public String getCode() { return code; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
}
