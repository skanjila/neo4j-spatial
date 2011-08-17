/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial;

import org.junit.Test;
import org.neo4j.gis.spatial.encoders.SimplePropertyEncoder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.graphdb.Node;

public class RTreeIndexTest extends Neo4jTestCase {

	private SpatialDatabaseService curSpatialDatabaseServiceObject = null;
	private Layer curLayerObject = null;
	private RTreeIndex curRTreeIndexObj = null;
	
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		curSpatialDatabaseServiceObject = new SpatialDatabaseService(graphDb());
		curLayerObject= curSpatialDatabaseServiceObject.getOrCreateDefaultLayer("roads");
		curRTreeIndexObj = new RTreeIndex(curSpatialDatabaseServiceObject.getDatabase(),curLayerObject,1,51);
	}
	
	
	
	@Test
	public void testAdd()
	{
		Transaction curTrans=null;
		Node geomTestNode = null;
		int prevCount=0;
		int curCount=0;
		Double [] bboxProps={1.2,1.0,2.0,3.0};
		try
		{
			curTrans=graphDb().beginTx();
			geomTestNode=graphDb().createNode();
			prevCount=curRTreeIndexObj.count();
			if (geomTestNode!=null)
			{
				geomTestNode.setProperty("test", "testtest");
				geomTestNode.setProperty("test1", "testtest1");
				geomTestNode.setProperty("bbox", bboxProps);
			}
			curRTreeIndexObj.add(geomTestNode);
			curCount=curRTreeIndexObj.count();
			assertTrue(curCount==prevCount+1);
			curTrans.success();
		}
		catch ( Exception e )
        {
			curTrans.failure();
        } 
		finally
        {
        	curTrans.finish();
        }

		assert(curCount==prevCount+1);
	}
	
	
	
	/** We want to first add a node
	 * and then remove that same node, we then
	 * want to check the index for that node
	 * and it shouldnt exist at that point
	 */
	@Test
	public void testRemove()
	{
		Transaction curTrans=null;
		Node geomTestNode = null;
		int prevCount=0;
		int curCount=0;
		Double [] bboxProps={1.2,1.0,2.0,3.0};
		try
		{
			
			//the code to add the node as above
			curTrans=graphDb().beginTx();
			geomTestNode=graphDb().createNode();
			prevCount=curRTreeIndexObj.count();
			if (geomTestNode!=null)
			{
				geomTestNode.setProperty("test", "testtest");
				geomTestNode.setProperty("test1", "testtest1");
				geomTestNode.setProperty("bbox", bboxProps);
			}
			curRTreeIndexObj.add(geomTestNode);
			curCount=curRTreeIndexObj.count();
			assertTrue(curCount==prevCount+1);
			
			
			//now we remove
			curRTreeIndexObj.remove(geomTestNode.getId(), true);
			
			//and we check the total number of nodes
			curCount=curRTreeIndexObj.count();
			assertTrue(curCount==prevCount);
			curTrans.success();
		}
		catch ( Exception e )
        {
			curTrans.failure();
        } 
		finally
        {
        	curTrans.finish();
        }

		assert(curCount==prevCount+1);
	}
	
	
	
	
	
	/** We want to remove a set of nodes
	 * and then check to see whether we have
	 * any nodes left by querying the count
	 */
	@Test
	public void testRemoveAll()
	{
		Transaction curTrans=null;
		Node geomTestNode = null;
		Node geomTestNode1 = null;
		Node geomTestNode2 = null;
		Node geomTestNode3 = null;
		int prevCount=0;
		int curCount=0;
		Double [] bboxProps={1.2,1.0,2.0,3.0};
		Double [] bbox1Props={1.2,4.0,7.0,2.0};
		Double [] bbox2Props={2.2,3.0,6.0,8.0};
		Double [] bbox3Props={1.9,4.5,5.0,9.0};
		try
		{
			
			//the code to add the node as above
			curTrans=graphDb().beginTx();
			geomTestNode=graphDb().createNode();
			geomTestNode1=graphDb().createNode();
			geomTestNode2=graphDb().createNode();
			geomTestNode3=graphDb().createNode();
			prevCount=curRTreeIndexObj.count();
			if (geomTestNode!=null)
			{
				geomTestNode.setProperty("test", "testtest");
				geomTestNode.setProperty("test1", "testtest1");
				geomTestNode.setProperty("bbox", bboxProps);
			}
			if (geomTestNode1!=null)
			{
				geomTestNode1.setProperty("test1", "testtest1");
				geomTestNode1.setProperty("test2", "testtest2");
				geomTestNode1.setProperty("bbox", bbox1Props);
			}
			if (geomTestNode2!=null)
			{
				geomTestNode2.setProperty("test2", "testtest3");
				geomTestNode2.setProperty("test3", "testtest4");
				geomTestNode2.setProperty("bbox", bbox2Props);
			}
			if (geomTestNode3!=null)
			{
				geomTestNode2.setProperty("test4", "testtest5");
				geomTestNode2.setProperty("test5", "testtest6");
				geomTestNode2.setProperty("bbox", bbox3Props);
			}
			curRTreeIndexObj.add(geomTestNode);
			curRTreeIndexObj.add(geomTestNode1);
			curRTreeIndexObj.add(geomTestNode2);
			curRTreeIndexObj.add(geomTestNode3);
			curCount=curRTreeIndexObj.count();
			assertTrue(curCount==4);
			
			
			//now we remove all the nodes, to do this
			//we establish a listener
			curRTreeIndexObj.removeAll(true, new NullListener());

			
			//and we check the total number of nodes is zero
			curCount=curRTreeIndexObj.count();
			assertTrue(curCount==0);
			curTrans.success();
		}
		catch ( Exception e )
        {
			curTrans.failure();
        } 
		finally
        {
        	curTrans.finish();
        }

		assert(curCount==prevCount+1);
	}
	
	
	
}
