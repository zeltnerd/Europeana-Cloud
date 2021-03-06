package eu.europeana.cloud.service.aas.authentication;

import eu.europeana.cloud.common.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;

/**
 * Provides core information for every user interacting with the ecloud.
 * Implementation compatible with spring security.
 *
 * (username, password..)
 *
 * @author emmanouil.koufakis@theeuropeanlibrary.org
 */
public class SpringUser extends User implements UserDetails {
    private static final long serialVersionUID = 1L;

	private List<GrantedAuthority> roles  = new ArrayList<GrantedAuthority>(0);

    public SpringUser(final String username, final String password, final Set<String> userRoles) {
        super(username, password);
        this.roles = mapToStringRoles(userRoles);
    }

    public SpringUser(final String username, final String password) {
        super(username, password);
    }
    
    private List<GrantedAuthority> mapToStringRoles(final Set<String> userRoles) {
    	 
		Set<GrantedAuthority> setAuths = new HashSet<GrantedAuthority>();
 
		// Build authorities
		for (String userRole : userRoles) {
			setAuths.add(new SimpleGrantedAuthority(userRole));
		}
 
		List<GrantedAuthority> result = new ArrayList<GrantedAuthority>(setAuths);
		return result;
	}

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
