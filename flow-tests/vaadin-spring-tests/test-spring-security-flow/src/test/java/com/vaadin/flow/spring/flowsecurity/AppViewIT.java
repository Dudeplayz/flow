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
    public void private_page_logout_should_redirect_to_root() {
        open(LOGIN_PATH);
        loginUser();
        navigateTo("private");
        clickLogout();
        assertRootPageShown();
    }

    @Test
    public void logout_redirects_to_root_page() {
        open(LOGIN_PATH);
        loginUser();
        navigateTo("private");
        assertPrivatePageShown(USER_FULLNAME);
        clickLogout();
        assertRootPageShown();
    }
    @Test
    public void redirect_to_resource_after_login() {
        String contents = "Secret document for admin";
        String path = "admin-only/secret.txt";
        openResource(path);
        loginAdmin();
        assertResourceShown(path);
        String result = getDriver().getPageSource();
        Assert.assertTrue(result.contains(contents));
    }

    @Test
    public void refresh_when_logged_in_stays_logged_in() {
        open("private");
        loginUser();
        assertPrivatePageShown(USER_FULLNAME);
        refresh();
        assertPrivatePageShown(USER_FULLNAME);
    }


    @Test
    public void access_restricted_to_admin() {
        String contents = "Secret document for admin";
        String path = "admin-only/secret.txt";
        openResource(path);
        assertLoginViewShown();
        loginUser();
        openResource(path);
        assertForbiddenPage();
        logout();

        openResource(path);
        loginAdmin();
        String adminResult = getDriver().getPageSource();
        Assert.assertTrue(adminResult.contains(contents));
        logout();
        openResource(path);
        assertLoginViewShown();
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

    private void navigateTo(String path) {
        navigateTo(path, true);
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


}
