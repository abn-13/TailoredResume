package com.bu.tailorsg.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Uploads over spring.servlet.multipart.max-file-size are rejected before any controller runs, so the
 * friendly message has to come from here: problem JSON for the API, a flash message for the page.
 */
@ControllerAdvice
public class UploadLimitAdvice {

    static final String MESSAGE = "The file is larger than 5 MB. A resume should be much smaller.";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object tooLarge(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, MESSAGE);
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
        }
        RequestContextUtils.getOutputFlashMap(request).put("flashError", MESSAGE);
        return new ModelAndView("redirect:/master/import");
    }
}
