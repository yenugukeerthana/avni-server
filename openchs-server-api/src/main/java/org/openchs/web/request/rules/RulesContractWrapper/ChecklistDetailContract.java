package org.openchs.web.request.rules.RulesContractWrapper;

import org.openchs.web.request.CHSRequest;

public class ChecklistDetailContract {

    private CHSRequest detail;

    public CHSRequest getDetail() {
        return detail;
    }

    public void setDetail(CHSRequest detail) {
        this.detail = detail;
    }
}
