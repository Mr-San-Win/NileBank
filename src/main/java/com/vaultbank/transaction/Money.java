package com.vaultbank.transaction;
import com.vaultbank.common.ApiException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
public final class Money {
    private Money(){}
    public static BigDecimal validate(BigDecimal amount){
        if(amount==null||amount.signum()<=0||amount.scale()>2||amount.compareTo(new BigDecimal("1000000.00"))>0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Amount must be positive, at most 1,000,000 THB, with at most 2 decimal places");
        return amount.setScale(2);
    }
}
