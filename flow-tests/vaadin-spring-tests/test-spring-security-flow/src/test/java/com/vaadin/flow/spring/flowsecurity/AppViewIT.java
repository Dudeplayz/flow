package com.vaadin.flow.spring.flowsecurity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.upload.testbench.UploadElement;
import com.vaadin.flow.spring.flowsecurity.views.PublicView;
import com.vaadin.testbench.TestBenchElement;

import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

public class AppViewIT extends AbstractIT {

    private static final String LOGIN_PATH = "my/login/page";
    private static final String USER_FULLNAME = "John the User";
    private static final String ADMIN_FULLNAME = "Emma the Admin";

    private void logout() {
        if (!$(ButtonElement.class).attribute("id", "logout").exists()) {
            open("");
            assertRootPageShown();
        }
        clickLogout();
        assertRootPageShown();
    }

    private void clickLogout() {
        getMainView().$(ButtonElement.class).id("logout").click();
    }


    @Test
    public void public_app_resources_available_for_all() {
        openResource("public/public.txt");
        checkForBrowserErrors();
        String shouldBeTextFile = getDriver().getPageSource();
        checkForBrowserErrors();
        Assert.assertTrue(
                shouldBeTextFile.contains("Public document for all users"));
                checkForBrowserErrors();
                open(LOGIN_PATH);
        loginUser();
        checkForBrowserErrors();
        openResource("public/public.txt");
        checkForBrowserErrors();
        shouldBeTextFile = getDriver().getPageSource();
        checkForBrowserErrors();
        Assert.assertTrue(
                shouldBeTextFile.contains("Public document for all users"));
                checkForBrowserErrors();
            }


    private void navigateTo(String path, boolean assertPathShown) {
        getMainView().$("a").attribute("href", path).first().click();
        if (assertPathShown) {
            assertPathShown(path);
        }
    }

    private TestBenchElement getMainView() {
        return waitUntil(driver -> $("*").id("main-view"));
    }

    private void refresh() {
        getDriver().navigate().refresh();
    }

    private void assertForbiddenPage() {
        assertPageContains(
                "There was an unexpected error (type=Forbidden, status=403).");
    }

    private void assertPageContains(String contents) {
        String pageSource = getDriver().getPageSource();
        Assert.assertTrue(pageSource.contains(contents));
    }

    private List<MenuItem> getMenuItems() {
        List<TestBenchElement> anchors = getMainView().$("vaadin-tabs").first()
                .$("a").all();

        return anchors.stream().map(anchor -> {
            String href = (String) anchor.callFunction("getAttribute", "href");
            String text = anchor.getPropertyString("textContent");
            boolean available = true;
            if (text.endsWith((" (hidden)"))) {
                text = text.replace(" (hidden)", "");
                available = false;
            }
            return new MenuItem(href, text, available);
        }).collect(Collectors.toList());
    }

}
