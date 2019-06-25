package org.jenkinsci.test.acceptance.utils.mail;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.mail.Address;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jenkinsci.test.acceptance.ByFactory;
import org.jenkinsci.test.acceptance.guice.TestScope;
import org.jenkinsci.test.acceptance.plugins.email_ext.GlobalConfig;
import org.jenkinsci.test.acceptance.plugins.mailer.MailerGlobalConfig;
import org.jenkinsci.test.acceptance.po.Jenkins;
import org.jenkinsci.test.acceptance.po.PageObject;

/**
 * {@link MailService} that uses a MailHog server (https://github.com/mailhog/MailHog).
 *
 * @author Andreas Pabst
 * @author Fabian Janker
 */
@TestScope
public class MailHog extends MailService {
    private final int port;
    private final int apiPort;
    private final String host;

    /**
     * Unique ID for this test run.
     */
    public final String fingerprint;

    /**
     * Create an interface to a MailHog server. Use the default host 'localhost' and default port 1025.
     */
    public MailHog() {
        this("localhost", 1025, 8025);
    }

    /**
     * Create an interface to a MailHog server.
     *
     * @param host
     *         The host, the MailHog server is running at
     * @param port
     *         The port, the MailHog server is running at
     */
    public MailHog(final String host, final int port, final int apiPort) {
        this.host = host;
        this.port = port;
        this.apiPort = apiPort;
        this.fingerprint = PageObject.createRandomName() + "@localhost";
    }

    /**
     * Set up the configuration to use MailHog.
     */
    @Override
    public void setup(final Jenkins jenkins) {
        jenkins.configure();
        MailerGlobalConfig config = new MailerGlobalConfig(jenkins);
        config.smtpServer.set(host);
        config.advancedButton.click();
        config.smtpPort.set(port);

        // Fingerprint to identify message sent from this test run
        config.replyToAddress.set(fingerprint);

        jenkins.save();

        // Set for email-ext plugin as well if available
        if (jenkins.getPluginManager().isInstalled("email-ext")) {
            // For whatever reason this needs new config page opened
            jenkins.configure();
            GlobalConfig ext = new GlobalConfig(jenkins.getConfigPage());
            ext.smtpServer(host);
            ext.smtpPort(port);
            ext.replyTo(fingerprint);
            jenkins.save();
        }
    }

    /**
     * Return the Mail message having a specific subject.
     *
     * @param subject
     *         the subject to match
     *
     * @return null if nothing found, the message otherwise
     * @throws AssertionError
     *         if more than one message matches the subject
     */
    @Override
    public MimeMessage getMail(final Pattern subject) throws IOException {
        List<MimeMessage> match = new ArrayList<>();

        for (MimeMessage msg : getAllMails()) {
            try {
                if (subject.matcher(msg.getSubject()).find()) {
                    match.add(msg);
                }
            }
            catch (MessagingException e) {
                throw new AssertionError(e);
            }
        }

        switch (match.size()) {
            case 0:
                return null;
            case 1:
                return match.get(0);
            default:
                throw new AssertionError("More than one matching message found");
        }
    }

    @Override
    public List<MimeMessage> getAllMails() throws IOException {
        List<MimeMessage> match = new ArrayList<>();

        for (JsonNode msg : fetchMessages().get("items")) {
            MimeMessage m = getMimeMessage(msg);

            if (isOurs(m)) {
                match.add(m);
            }
        }

        return match;
    }

    private MimeMessage getMimeMessage(final JsonNode jsonNode) {
        MimeMessage m = new MimeMessage(Session.getDefaultInstance(System.getProperties()));

        Address[] replyToAddresses = getAddresses(jsonNode.get("Content").get("Headers").get("Reply-To"));
        Address[] receiverAddresses = getAddresses(jsonNode.get("Content").get("Headers").get("To"));

        try {
            m.setReplyTo(replyToAddresses);
            m.setRecipients(RecipientType.TO, receiverAddresses);
            m.setSubject(jsonNode.get("Content").get("Headers").get("Subject").get(0).asText());
            m.setText(jsonNode.get("MIME").get("Parts").get(0).get("Body").asText());
        }
        catch (MessagingException e) {
            throw new AssertionError(e);
        }

        return m;
    }

    private Address[] getAddresses(final JsonNode node) {
        return StreamSupport
                .stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .map(add -> {
                    try {
                        return new InternetAddress(add);
                    }
                    catch (AddressException e) {
                        throw new AssertionError(e);
                    }
                })
                .toArray(Address[]::new);
    }

    /**
     * Does this email belong to our test case (as opposed to other tests that might be running elsewhere?).
     */
    private boolean isOurs(final MimeMessage m) {
        try {
            Address[] r = m.getReplyTo();
            if (r == null) {
                return false;
            }
            for (Address a : r) {
                if (a.toString().contains(fingerprint)) {
                    return true;
                }
            }
            return false;
        }
        catch (MessagingException e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode fetchMessages() throws IOException {
        String s = IOUtils.toString(new URL(String.format("http://%s:%d/api/v2/messages", host, apiPort))
                .openStream());
        return new ObjectMapper().readTree(s);
    }
}
