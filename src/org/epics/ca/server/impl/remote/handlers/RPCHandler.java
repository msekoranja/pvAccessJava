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

import org.epics.ca.client.ChannelRPC;
import org.epics.ca.client.ChannelRPCRequester;
import org.epics.ca.impl.remote.ChannelHostingTransport;
import org.epics.ca.impl.remote.IntrospectionRegistry;
import org.epics.ca.impl.remote.QoS;
import org.epics.ca.impl.remote.Transport;
import org.epics.ca.impl.remote.TransportSendControl;
import org.epics.ca.impl.remote.TransportSender;
import org.epics.ca.server.impl.remote.ServerChannelImpl;
import org.epics.ca.server.impl.remote.ServerContextImpl;
import org.epics.pvData.misc.BitSet;
import org.epics.pvData.pv.PVStructure;
import org.epics.pvData.pv.Status;

/**
 * RPC handler.
 * @author <a href="mailto:matej.sekoranjaATcosylab.com">Matej Sekoranja</a>
 * @version $Id$
 */
public class RPCHandler extends AbstractServerResponseHandler {

	/**
	 * @param context
	 */
	public RPCHandler(ServerContextImpl context) {
		super(context, "RPC request");
	}

    
	private static class ChannelRPCRequesterImpl extends BaseChannelRequester implements ChannelRPCRequester, TransportSender {
		
		private volatile ChannelRPC channelRPC;
		private volatile PVStructure pvArguments;
		private PVStructure pvResponse;
		private volatile BitSet agrumentsBitSet;
		private Status status;
		
		public ChannelRPCRequesterImpl(ServerContextImpl context, ServerChannelImpl channel, int ioid, Transport transport,
				PVStructure pvRequest) {
			super(context, channel, ioid, transport);
			
			startRequest(QoS.INIT.getMaskValue());
			channel.registerRequest(ioid, this);
			channelRPC = channel.getChannel().createChannelRPC(this, pvRequest);
			// TODO what if last call fails... registration is still present
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.client.ChannelRPCRequester#channelRPCConnect(org.epics.pvData.pv.Status, org.epics.ca.client.ChannelRPC, org.epics.pvData.pv.PVStructure, org.epics.pvData.misc.BitSet)
		 */
		@Override
		public void channelRPCConnect(Status status, ChannelRPC channelRPC, PVStructure arguments, BitSet bitSet) {
			synchronized (this) {
				this.pvArguments = arguments;
				this.agrumentsBitSet = bitSet;
				this.status = status;
			}
			transport.enqueueSendRequest(this);

			// self-destruction
			if (!status.isSuccess()) {
				destroy();
			}
		}

		
		/* (non-Javadoc)
		 * @see org.epics.ca.client.ChannelRPCRequester#requestDone(org.epics.pvData.pv.Status, org.epics.pvData.pv.PVStructure)
		 */
		@Override
		public void requestDone(Status status, PVStructure pvResponse) {
			synchronized (this)
			{
				this.status = status;
				this.pvResponse = pvResponse;
			}
			transport.enqueueSendRequest(this);
		}

		/* (non-Javadoc)
		 * @see org.epics.pvData.misc.Destroyable#destroy()
		 */
		@Override
		public void destroy() {
			channel.unregisterRequest(ioid);
			if (channelRPC != null)
				channelRPC.destroy();
		}

		/**
		 * @return the channelRPC
		 */
		public ChannelRPC getChannelRPC() {
			return channelRPC;
		}

		/**
		 * @return the pvArguments
		 */
		public PVStructure getPvArguments() {
			return pvArguments;
		}

		/**
		 * @return the agrumentsBitSet
		 */
		public BitSet getAgrumentsBitSet() {
			return agrumentsBitSet;
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#lock()
		 */
		@Override
		public void lock() {
			// TODO
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#unlock()
		 */
		@Override
		public void unlock() {
			// TODO
		}

		/* (non-Javadoc)
		 * @see org.epics.ca.impl.remote.TransportSender#send(java.nio.ByteBuffer, org.epics.ca.impl.remote.TransportSendControl)
		 */
		@Override
		public void send(ByteBuffer buffer, TransportSendControl control) {
			final int request = getPendingRequest();

			control.startMessage((byte)20, Integer.SIZE/Byte.SIZE + 1);
			buffer.putInt(ioid);
			buffer.put((byte)request);
			final IntrospectionRegistry introspectionRegistry = transport.getIntrospectionRegistry();
			synchronized (this) {
				introspectionRegistry.serializeStatus(buffer, control, status);
			}

			if (status.isSuccess())
			{
				if (QoS.INIT.isSet(request))
				{
					introspectionRegistry.serialize(pvArguments != null ? pvArguments.getField() : null, buffer, control);
				}
				else
				{
					introspectionRegistry.serializeStructure(buffer, control, pvResponse);
				}
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

		final byte qosCode = payloadBuffer.get();

		final ServerChannelImpl channel = (ServerChannelImpl)casTransport.getChannel(sid);
		if (channel == null) {
			BaseChannelRequester.sendFailureMessage((byte)20, transport, ioid, qosCode, BaseChannelRequester.badCIDStatus);
			return;
		}
		
		final boolean init = QoS.INIT.isSet(qosCode);
		if (init)
		{
			/*
			// check process access rights
			if (process && !AccessRights.PROCESS.isSet(channel.getAccessRights()))
			{
				putGetFailureResponse(transport, ioid, qosCode, BaseChannelRequester.noProcessACLStatus);
				return;
			}
			*/

			// pvRequest
		    final PVStructure pvRequest = transport.getIntrospectionRegistry().deserializePVRequest(payloadBuffer, transport);
		    
			// create...
		    new ChannelRPCRequesterImpl(context, channel, ioid, transport, pvRequest);
		}
		else
		{
			final boolean lastRequest = QoS.DESTROY.isSet(qosCode);
			
			ChannelRPCRequesterImpl request = (ChannelRPCRequesterImpl)channel.getRequest(ioid);
			if (request == null) {
				BaseChannelRequester.sendFailureMessage((byte)20, transport, ioid, qosCode, BaseChannelRequester.badIOIDStatus);
				return;
			}

			if (!request.startRequest(qosCode)) {
				BaseChannelRequester.sendFailureMessage((byte)20, transport, ioid, qosCode, BaseChannelRequester.otherRequestPendingStatus);
				return;
			}

			/*
			// check write access rights
			if (!AccessRights.WRITE.isSet(channel.getAccessRights()))
			{
				putGetFailureResponse(transport, ioid, qosCode, BaseChannelRequester.noWriteACLStatus);
				if (lastRequest)
					request.destroy();
				return;
			}
			 */
			
			/*
			// check read access rights
			if (!AccessRights.READ.isSet(channel.getAccessRights()))
			{
				putGetFailureResponse(transport, ioid, qosCode, BaseChannelRequester.noReadACLStatus);
				if (lastRequest)
					request.destroy();
				return;
			}
			*/

			// deserialize put data
			final BitSet changedBitSet = request.getAgrumentsBitSet();
			changedBitSet.deserialize(payloadBuffer, transport);
			request.getPvArguments().deserialize(payloadBuffer, transport, changedBitSet);
			request.getChannelRPC().request(lastRequest);
		}
	}
}