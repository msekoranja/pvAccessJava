package org.epics.ca.impl.remote;

public interface ServerChannel {

	/**
	 * Get channel SID.
	 * @return channel SID.
	 */
	public int getSID();

	/**
	 * Destroy server channel.
	 * This method MUST BE called if overriden.
	 */
	public void destroy();

}