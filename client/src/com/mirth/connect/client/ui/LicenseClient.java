/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.client.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.Timer;
import java.util.TimerTask;

import javax.swing.SwingUtilities;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.util.StringUtil;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.model.LicenseInfo;

public class LicenseClient {

    private static Timer timer;
    private static boolean isLicenseExpired = false;

    public static void start() {
        stop();

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                check();
            }
        };

        timer = new Timer(true);
        timer.scheduleAtFixedRate(task, 0, 24L * 60L * 60L * 1000L);
    }

    public static void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    private static void check() {
        try {
            LicenseInfo licenseInfo = PlatformUI.MIRTH_FRAME.mirthClient.getLicenseInfo();
            String property = PlatformUI.MIRTH_FRAME.mirthClient.getProperty("padlock", "padlockMessage");
            final ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());
            StringBuilder builder = new StringBuilder("<html> ");
            boolean invalidLicense = false;

            if (licenseInfo.getReason() != null) {
                invalidLicense = true;
                builder.append(licenseInfo.getReason().replace("\n", "<br/>")).append("<br/>");
            }
            if ((licenseInfo.getExpirationDate() != null && licenseInfo.getExpirationDate() > 0)) {

                final ZonedDateTime expiration = ZonedDateTime.ofInstant(Instant.ofEpochMilli(licenseInfo.getExpirationDate()), ZoneId.systemDefault());

                Long warningPeriod = licenseInfo.getWarningPeriod();
                if (warningPeriod == null) {
                    warningPeriod = 60L * 24L * 60L * 60L * 1000L;	// 60 days
                }
                
                ZonedDateTime warningStart = expiration.minus(Duration.ofMillis(warningPeriod));

                if (now.isAfter(expiration) || now.isAfter(warningStart)) {
                    invalidLicense = true;
                    builder.append("Your NextGen Connect license for the extensions<br/>[").append(StringUtils.join(licenseInfo.getExtensions(), ", ")).append("]<br/>");

                    if (now.isAfter(expiration)) {
                        isLicenseExpired = true;
                        builder.append(" has expired. ");
                    } else {
                        builder.append(" will expire in ");
                        int days = (int) Math.ceil((double) Duration.between(now, expiration).getSeconds() / 60 / 60 / 24);
                        builder.append(days).append(" day").append(days == 1 ? "" : "s");
                    }

                }

            }
            if (invalidLicense) {
                builder.append("<br/>Please create a support ticket through the Success Community client portal<br/>or contact us at mirthconnectsales@nextgen.com for assistance with your commercial license. </html>");
                final String message = builder.toString();

                SwingUtilities.invokeLater(() -> {
                    if (isLicenseExpired) {
                        PlatformUI.MIRTH_FRAME.alertError(PlatformUI.MIRTH_FRAME, message);
                    } else {
                        PlatformUI.MIRTH_FRAME.alertWarning(PlatformUI.MIRTH_FRAME, message);
                    }
                });
            }
            
            if (!StringUtil.isBlank(property)) {
                PlatformUI.MIRTH_FRAME.updatePadlockWarning(property);
            } else {
                if (StringUtils.isBlank(property) && !StringUtils.isBlank(PlatformUI.MIRTH_FRAME.getPadlockWarning())) {
                    PlatformUI.MIRTH_FRAME.updatePadlockWarning(null);
                }
            }
            
        } catch (ClientException e) {
            // Ignore
        }
    }
}
