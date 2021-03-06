package org.epics.pvaccess.client.pvds.util.test;

import junit.framework.TestCase;

import org.epics.pvaccess.client.pvds.util.BloomFilter;
import org.epics.pvaccess.client.pvds.util.StringToByteArraySerializator;

public class BloomFilterTest extends TestCase {

	/**
	 * @param name
	 */
	public BloomFilterTest(String name) {
		super(name);
	}
	
	public void testCalculations()
	{
		BloomFilter<String> f;
		f = new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, 0.1, 100);
		assertEquals(3, f.k());
		assertEquals(512, f.m());

		f = new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, 0.03, 1000);
		assertEquals(5, f.k());
		assertEquals(8000, f.m());

		f = new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, 0.01, 123457);
		assertEquals(6, f.k());
		assertEquals(1234624, f.m());

		f = new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, 0.005, 1000001);
		assertEquals(8, f.k());
		assertEquals(12000064, f.m());
	}
	
	public void testFilter()
	{
		final double p = 0.001;
		final int COUNT = 100000;
		BloomFilter<String> f =
			new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, p, COUNT);
		
		for (int i = 0; i < COUNT; i++)
			f.add(String.valueOf(i));
		
		assertEquals(COUNT, f.elements());
		
		for (int i = 0; i < COUNT; i++)
			assertTrue(f.contains(String.valueOf(i)));

		// p estimate check
		assertEquals(0.00074, f.p(), 0.00001);

		// false positive experimental check
		int fpSamples = (int)(100/p);
		int fpCount = 0;
		for (int i = 0; i < fpSamples; i++)
			if (f.contains("x" + String.valueOf(i)))
				fpCount++;
		assertEquals(p, fpCount/(double)fpSamples, 3*p/10);
	}
	
	public void testReset()
	{
		final double p = 0.1;
		final int COUNT = 10;
		BloomFilter<String> f =
			new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, p, COUNT);

		assertEquals(f.elements(), 0);
		assertEquals(f.bitSet().cardinality(), 0);
		
		for (int i = 0; i < COUNT; i++)
			f.add(String.valueOf(i));
		
		assertEquals(f.elements(), COUNT);
		assertTrue(f.bitSet().cardinality() != 0);

		f.reset();
		
		assertEquals(f.elements(), 0);
		assertEquals(f.bitSet().cardinality(), 0);
	}
	
	public void testToString()
	{
		BloomFilter<String> f =
			new BloomFilter<String>(StringToByteArraySerializator.INSTANCE, 0.3, 100);
		assertNotNull(f.toString());
		assertFalse(f.toString().isEmpty());
	}
	
}
