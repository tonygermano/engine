package com.mirth.connect.connectors.core.http;

import java.util.List;
import java.util.Map;

import com.mirth.connect.donkey.util.DonkeyElement;

public interface IHttpDispatcherProperties {
    
    String getMethod();
    
    void setMethod(String method);
    
    String getProtocol();
    
    String toFormattedString();
    
    String getHost();
    
    void setHost(String host);
    
    Map<String, List<String>> getParametersMap();
    
    String getUsername();
    
    String getContent();
    
    Map<String, Object> getPurgedProperties();
    
    void migrate3_7_0(DonkeyElement element);
    
    void migrate3_9_0(DonkeyElement element);
    
    String getName();
    
    void setSocketTimeout(String socketTimeout);
    
    Map<String, List<String>> getHeadersMap();

    void setUseAuthentication(boolean useAuthentication);
    
    void setUsername(String username);
    
    void setPassword(String password);
    
    void setParametersMap(Map<String, List<String>> queryPathParameters);

    String getSocketTimeout();
    
    boolean isUseAuthentication();

    String getPassword();

    boolean isUseParametersVariable();

    String getParametersVariable();    
    
    void setContentType(String contentType);
    
    boolean isResponseBinaryMimeTypesRegex();
}
