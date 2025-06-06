/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import static org.apache.sshd.client.session.ClientSession.REMOTE_COMMAND_WAIT_EVENTS;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.CloseableScpClient;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@link ComputerLauncher} for EC2 that wraps the real user-specified {@link ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2ComputerLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer) slaveComputer;
            launchScript(computer, listener);
        } catch (SdkException | IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() instanceof EC2AbstractSlave ec2AbstractSlave) {
                LOGGER.log(
                        Level.FINE,
                        String.format(
                                "Terminating the ec2 agent %s due a problem launching or connecting to it",
                                slaveComputer.getName()),
                        e);
                ec2AbstractSlave.terminate();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() instanceof EC2AbstractSlave ec2AbstractSlave) {
                LOGGER.log(
                        Level.FINE,
                        String.format(
                                "Terminating the ec2 agent %s due a problem launching or connecting to it",
                                slaveComputer.getName()),
                        e);
                ec2AbstractSlave.terminate();
            }
        }
    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launchScript(EC2Computer computer, TaskListener listener)
            throws SdkException, IOException, InterruptedException;

    protected int waitCompletion(ClientChannel clientChannel, long timeout) {
        Set<ClientChannelEvent> clientChannelEvents = clientChannel.waitFor(REMOTE_COMMAND_WAIT_EVENTS, timeout);
        if (clientChannelEvents.contains(ClientChannelEvent.TIMEOUT)) {
            return -1;
        } else {
            return clientChannel.getExitStatus();
        }
    }

    protected CloseableScpClient createScpClient(ClientSession session) {
        ScpClientCreator creator = ScpClientCreator.instance();
        ScpClient client = creator.createScpClient(session);
        return CloseableScpClient.singleSessionInstance(client);
    }
}
