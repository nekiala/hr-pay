package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/** Employee pickers search on the server and never load the whole staff list. */
class PeopleSearchIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    private List<String> names(MockHttpSession s, String query) throws Exception {
        String body = mvc.perform(get("/api/org/my-people/search?" + query).session(s)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].name");
    }

    @Test
    void hrFindsAnyoneByPartOfTheirNameOrIdAndAHeadOnlyTheirBranch() throws Exception {
        MockHttpSession hr = login(HR);
        long employee = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        String name = users.findById(employee).orElseThrow().getLastName().toUpperCase();

        // Every word must match, in any order, in the name, the employee ID or the email.
        assertThat(names(hr, "q=" + name.substring(0, 3).toLowerCase())).anyMatch(n -> n.contains(name));
        assertThat(names(hr, "q=" + name + "%20zzzz")).isEmpty();
        assertThat(names(hr, "q=EMP-0")).isNotEmpty().allMatch(n -> !n.isBlank());
        // At most the size asked for.
        assertThat(names(hr, "size=2")).hasSize(2);
        // A chosen person is given back by id.
        assertThat(names(hr, "ids=" + employee)).singleElement().matches(n -> n.contains(name));

        // An employee heads no one: nobody to choose.
        assertThat(names(login(EMPLOYEE), "q=a")).isEmpty();
        // A head finds the people of their branch, not HR.
        List<String> branch = names(login("manager@demo.hrpay.cd"), "size=50");
        assertThat(branch).anyMatch(n -> n.contains(name));
        assertThat(names(login("manager@demo.hrpay.cd"), "q=hr@demo")).isEmpty();
    }
}
