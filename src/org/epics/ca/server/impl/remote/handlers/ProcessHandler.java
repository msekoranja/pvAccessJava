/*
 * Copyright (c) 2009 by Cosylab
 *
 * The full license specifying the redistribution, modification, usage and other
 * rights and obligations is included with the distribution of this project in
 * the file "LICENSE-CAJ". If the license is not included visit Cosylab web site,
 * <http://www.cosylab.com>.
 *
 * THIS SOFTWARE IS PROVIDED AS-IS WITHOUT WARRANTY OF ANY KIND, NOT EVEN THE
 * IMPLIED WARRANTY OF MERCHANTABILITY. THE AUTHOR OF THIS SOFTWARE, ASSUMES
 * _NO_ RESPONSIBILITY FOR ANY CONSEQUENCE RESULTING FROM THE USE, MODIFICATION,
 * OR REDISTRIBUTION OF THIS SOFTWARE.
 */

package org.epics.ca.server.impl.remote.handlers;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.epics.ca.client.ChannelProcess;
import org.epics.ca.client.ChannelProcessRequester;
import org.epics.ca.impl.remote.ChannelHostingTransport;
import org.epics.ca.impl.remote.IntrospectionRegistry;
import org.epics.ca.impl.remote.QoS;
import org.epics.ca.impl.remote.Transport;
import org.epics.ca.impl.remote.TransportSendControl;
import org.epics.ca.impl.remote.TransportSender;
import org.epics.ca.server.impl.remote.ServerChannelImpl;
import org.epics.ca.server.impl.remote.ServerContextImpl;
import org.epics.pvData.pv.PVStructure;
import org.epics.pvData.pv.Status;

/**
 * Process request handler.
 * @author <a href="mailto:matej.sekoranjaATcosylab.com">Matej Sekoranja</a>
 * @version $Id$
 */
public class ProcessHandler extends AbstractServerResponseHandler {

	/**
	 * @param context
	 */
	public ProcessHandler(ServerContextImpl context) {
		super(context, "Process request");
	}

	private static class ChannelProcessRequesterImpl extends BaseChannelRequester implements ChannelProcessRequester, TransportSender {
		
		private volatile ChannelProcess channelProcess;
		private Status status;
		
		public ChannelProcessRequesterImpl(ServerContextImpl context, ServerChannelImpl channel, int ioid, Transport transport,
				PVStructure pvRequest) {
			super(context, channel, ioid, transport);
			
			startRequest(QoS.INIT.getMaskValue());
			channel.registerRequest(ioid, this);
			channelProcess = channel.getChannel().createChannelProcess(this, pvRequest);
			// TODO what if last call fails... registration is still present
		}

		@Override
		public void channelProcessConnect(Status status, ChannelProcess channelProcess) {
			synchronized (this) {
				this.status = status;
				this.channelProcess = channelProcess;
			}
			transport.enqueueSendRequest(this);

			// self-destruction
			if (!status.isSuccess()) {
				destroy();
			}
		}
		
		@Override
		public void processDone(Status status) {
			synchronized (this) {
				this.status = status;
			}
			transport.enqueueSendRequest(this);
		}
		
		/* (non-Javadoc)
		 * @see org.epics.pvData.misc.Destroyable#destroy()
		 */
		@Override
		public void destroy() {
			channel.unregisterRequest(ioid);
			if (channelProcess != null)
				channelProcess.destroy();
		}

		/**
		 * @return the channelProcess
		 */
		public ChannelProcess getChannelProcess() {
			return channelProcess;
		}
		
		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#lock()
		 */
		@Override
		public void lock() {
			// noop
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#unlock()
		 */
		@Override
		public void unlock() {
			// noop
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#send(java.nio.ByteBuffer, org.epics.ca.impl.remote.TransportSendControl)
		 */
		@Override
		public void send(ByteBuffer buffer, TransportSendControl control) {
			final int request = getPendingRequest();

			control.startMessage((byte)16, Integer.SIZE/Byte.SIZE + 1);
			buffer.putInt(ioid);
			buffer.put((byte)request);
			final IntrospectionRegistry introspectionRegistry = transport.getIntrospectionRegistry();
			synchronized (this) {
				introspectionRegistry.serializeStatus(buffer, control, status);
			}

			stopRequest();

			// lastRequest
			if (QoS.DESTROY.isSet(request))
				destroy();
		}

	};

	/* (non-Javadoc)
	 * @see org.epics.ca.impl.remote.AbstractResponseHandler#handleResponse(java.net.InetSocketAddress, org.epics.ca.core.Transport, byte, byte, int, java.nio.ByteBuffer)
	 */
	@Override
	public void handleResponse(InetSocketAddress responseFrom, final Transport transport, byte version, byte command, int payloadSize, ByteBuffer payloadBuffer) {
		super.handleResponse(responseFrom, transport, version, command, payloadSize, payloadBuffer);

		// NOTE: we do not explicitly check if transport is OK
		final ChannelHostingTransport casTransport = (ChannelHostingTransport)transport;

		transport.ensureData(2*Integer.SIZE/Byte.SIZE+1);
		final int sid = payloadBuffer.getInt();
		final int ioid = payloadBuffer.getInt();

		// mode
		final byte qosCode = payloadBuffer.get();
		
		final ServerChannelImpl channel = (ServerChannelImpl)casTransport.getChannel(sid);
		if (channel == null) {
			BaseChannelRequester.sendFailureMessage((byte)16, transport, ioid, qosCode, BaseChannelRequester.badCIDStatus);
			return;
		}
		
		final boolean init = QoS.INIT.isSet(qosCode);
		if (init)
		{
		    // pvRequest
		    final PVStructure pvRequest = transport.getIntrospectionRegistry().deserializePVRequest(payloadBuffer, transport);

		    // create...
		    new ChannelProcessRequesterImpl(context, channel, ioid, transport, pvRequest);
		}
		else
		{
			final boolean lastRequest = QoS.DESTROY.isSet(qosCode);

			ChannelProcessRequesterImpl request = (ChannelProcessRequesterImpl)channel.getRequest(ioid);
			if (request == null) {
				BaseChannelRequester.sendFailureMessage((byte)16, transport, ioid, qosCode, BaseChannelRequester.badIOIDStatus);
				return;
			}

			if (!request.startRequest(qosCode)) {
				BaseChannelRequester.sendFailureMessage((byte)16, transport, ioid, qosCode, BaseChannelRequester.otherRequestPendingStatus);
				return;
			}

			/*
			// check write access rights
			if (!AccessRights.PROCESS.isSet(channel.getAccessRights()))
			{
				processResponse(transport, ioid, qosCode, BaseChannelRequester.noProcessACLStatus);
				if (lastRequest)
					request.destroy();
				return;
			}
			*/
			request.getChannelProcess().process(lastRequest);
		}
		
	}
}