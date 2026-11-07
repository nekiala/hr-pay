package cd.hrpay.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts {@link TenantDataSource} in front of the connection pool, for JPA, JDBC and sessions alike. */
@Configuration
public class TenancyConfig {

    @Bean
    static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantDataSource) && "dataSource".equals(beanName)) {
                    return new TenantDataSource(ds);
                }
                return bean;
            }
        };
    }
}
