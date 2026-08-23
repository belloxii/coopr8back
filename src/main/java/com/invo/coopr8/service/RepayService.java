package com.invo.coopr8.service;

import com.invo.coopr8.dto.RepayDto;
import com.invo.coopr8.dto.RepayResponse;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.exception.RepayException;
import com.invo.coopr8.model.User;

public interface RepayService {

    public RepayResponse repayNow(User user, Long loanId, RepayDto repayDto) throws LoanException, RepayException;
    
}
