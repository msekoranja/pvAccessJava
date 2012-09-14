package org.epics.pvaccess.client.rpc.test;

import java.util.logging.Logger;

import org.epics.pvaccess.client.rpc.ServiceClient;
import org.epics.pvaccess.client.rpc.ServiceClientImpl;
import org.epics.pvaccess.client.rpc.ServiceClientRequester;
import org.epics.pvaccess.server.rpc.RPCRequestException;
import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.pv.Field;
import org.epics.pvdata.pv.FieldCreate;
import org.epics.pvdata.pv.MessageType;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.ScalarType;
import org.epics.pvdata.pv.Status;
import org.epics.pvdata.pv.Structure;

public class RPCServiceClientExample {

	private final static FieldCreate fieldCreate = FieldFactory.getFieldCreate();
	
	private final static Structure requestStructure =
		fieldCreate.createStructure(
				new String[] { "a", "b" },
				new Field[] { fieldCreate.createScalar(ScalarType.pvString),
							  fieldCreate.createScalar(ScalarType.pvString) }
				);
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Throwable {
		try
		{
			PVStructure arguments = PVDataFactory.getPVDataCreate().createPVStructure(requestStructure);
			arguments.getStringField("a").put("12.3");
			arguments.getStringField("b").put("45.6");

			//
			// sync example
			//
			{
				ServiceClientImpl client = new ServiceClientImpl("sum");
				try
				{
					PVStructure result = client.request(arguments, 3.0); 
					System.out.println(result);
				} catch (RPCRequestException rre) {
					System.out.println(rre);
				}
				client.destroy();
			}
			
			//
			// async example
			//
			{
				ServiceClientRequesterImpl requester = new ServiceClientRequesterImpl();
				ServiceClientImpl client = new ServiceClientImpl("sum", requester);
				// we could sendRequest asynchronously, but this is soo much easier
				if (!client.waitConnect(3.0))
					throw new RuntimeException("connection timeout");
				
				client.sendRequest(arguments); 
				if (!client.waitResponse(3.0))
					throw new RuntimeException("response timeout");
				
				Status status = requester.getStatus();
				if (status.isSuccess())
					System.out.println(requester.getResult());
				else
					System.out.println(status);
			}
		}
		finally
		{
			org.epics.pvaccess.ClientFactory.stop();
		}
	}
	
	private static class ServiceClientRequesterImpl implements ServiceClientRequester
	{
	    private static final Logger logger = Logger.getLogger(ServiceClientRequesterImpl.class.getName());
	    
	    private volatile Status status;
	    private volatile PVStructure result;

	    @Override
		public String getRequesterName() {
			return getClass().getName();
		}

		@Override
		public void message(String message, MessageType messageType) {
			logger.finer(getRequesterName() + ": [" +  messageType + "] " + message);
		}

		@Override
		public void connectResult(ServiceClient client, Status status) {
			// noop
		}

		@Override
		public void requestResult(ServiceClient client, Status status, PVStructure pvResult) {
			this.status = status;
			this.result = pvResult;
		}

		/**
		 * @return the status
		 */
		public Status getStatus() {
			return status;
		}

		/**
		 * @return the result
		 */
		public PVStructure getResult() {
			return result;
		}
		
	}

}