package com.vaultbank.auth;
import com.vaultbank.user.*;
import com.vaultbank.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    public AuthService(UserRepository users,PasswordEncoder passwords){this.users=users;this.passwords=passwords;}
    public record UserView(UUID id,String fullName,String email,Instant createdAt){}
    public static String normalize(String email){return email.strip().toLowerCase(Locale.ROOT);}
    public static UserView view(BankUser u){return new UserView(u.getId(),u.getFullName(),u.getEmail(),u.getCreatedAt());}
    @Transactional
    public UserView register(String name,String email,String password){
        if(password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Password must fit within 72 UTF-8 bytes");
        String normalized=normalize(email);
        if(users.findByEmail(normalized).isPresent()) throw new ApiException(HttpStatus.CONFLICT,"Email unavailable");
        return view(users.saveAndFlush(new BankUser(name.strip(),normalized,passwords.encode(password))));
    }
    @Transactional(readOnly=true)
    public UserView me(UUID id){return view(users.findById(id).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"Please log in")));}
}
