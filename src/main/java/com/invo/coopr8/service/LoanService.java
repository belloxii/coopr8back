package com.invo.coopr8.service;

import com.invo.coopr8.dto.LoanDto;
import com.invo.coopr8.dto.LoanResponse;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.User;

public interface LoanService {

    public LoanResponse applyLoan(User user, LoanDto loanRequest) throws LoanException;
    public Loan approveLoan(Long loanId) throws LoanException;
    public Loan rejectLoan(Long loanId, LoanDto loanDto) throws LoanException;

}
