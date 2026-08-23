package com.invo.coopr8.service;

import com.invo.coopr8.dto.SharesResponse;
import com.invo.coopr8.exception.SharesException;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;

public interface SharesService {
    Shares addShares(User user, Shares sharesDetails) throws SharesException;
    SharesResponse withdrawShares(User user, Shares sharesDetails) throws SharesException;
    SharesResponse approveWithdraw(Long shareId) throws SharesException;
    SharesResponse declineWithdraw(Long shareId, Shares sharesDetails) throws SharesException;
}
