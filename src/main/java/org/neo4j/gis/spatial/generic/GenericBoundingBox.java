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
package org.neo4j.gis.spatial.generic;
import java.util.Iterator;

import org.apache.commons.lang.ArrayUtils;
import org.neo4j.gis.spatial.Constants;
import org.neo4j.gis.spatial.Layer;
import org.neo4j.gis.spatial.SpatialRelationshipTypes;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

public class GenericBoundingBox implements Constants {
	
	private double [] currentBoundingBoxParms;
	private Layer layer;
	private int minNodeReferences;
	
	public GenericBoundingBox(double[] vals)
	{
		for (int i=0;i<vals.length;i++)
			currentBoundingBoxParms[i]=vals[i];
	}
	
	
	public Node getIndexRoot() {
		return layer.getLayerNode().getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).getEndNode();
	}
	
	public GenericBoundingBox()
	{}
	
	
	/**
     * Create a bounding box encompassing the two bounding boxes passed in.
     */	
	public double [] createBoundingBox(double[] e, double[] e1) {
		double[] result = e;
		expandToInclude(result,e1);
		return result;
	}
	
	
	/**
     *  Enlarges this <code>Envelope</code> so that it contains
     *  the given point. 
     *  Has no effect if the point is already on or within the envelope.
     *
     *@param  x  the value to lower the minimum x to or to raise the maximum x to
     *@param  y  the value to lower the minimum y to or to raise the maximum y to
     */
    public void expandToInclude(double [] originalBbox,double [] otherBbox) {
        if (otherBbox[1]<otherBbox[0]) {
          return;
        }
        if (originalBbox[1]<currentBoundingBoxParms[0]) {
        	originalBbox[0] = otherBbox[0];
        	originalBbox[1] = otherBbox[1];
        	originalBbox[2] = otherBbox[2];
        	originalBbox[3] = otherBbox[3];
        }
        else {
          if (otherBbox[0] < originalBbox[0]) {
        	  originalBbox[0] = otherBbox[0];
          }
          if (otherBbox[1] > originalBbox[1]) {
        	  originalBbox[1] = otherBbox[1];
          }
          if (otherBbox[2] < originalBbox[2]) {
        	  originalBbox[2] = otherBbox[2];
          }
          if (otherBbox[3] > originalBbox[3]) {
        	  originalBbox[3] = otherBbox[3];
          }
        }
    }
    
    public int countChildren(Node indexNode, RelationshipType relationshipType) {
		int counter = 0;
		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			iterator.next();
			counter++;
		}
		return counter;
	}
    
    public Node getIndexNodeParent(Node indexNode) {
		Relationship relationship = indexNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
		if (relationship == null) return null;
		else return relationship.getStartNode();
	}	
    
    public double getArea(Node node) {
		return getArea(getLeafNodeBoundingBox(node));
	}


	public Node findIndexNodeToDeleteNearestToRoot(Node indexNode) {
		Node indexNodeParent = getIndexNodeParent(indexNode);
		
		if (getIndexNodeParent(indexNodeParent) != null && countChildren(indexNodeParent, SpatialRelationshipTypes.RTREE_CHILD) == minNodeReferences) {
			// indexNodeParent is not the root and will contain less than the minimum number of entries
			return findIndexNodeToDeleteNearestToRoot(indexNodeParent);
		} else {
			return indexNode;
		}
	}
	
	public void adjustPathBoundingBox(Node indexNode) {
		Node parent = getIndexNodeParent(indexNode);
		if (parent != null) {
			if (adjustParentBoundingBox(parent, (double[]) indexNode.getProperty(PROP_BBOX))) {
				// entry has been modified: adjust the path for the parent
				adjustPathBoundingBox(parent);
			}
		}
	}


	private boolean setMin(double[] parent, double[] child, int index) {
		if (parent[index] > child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}
	
	private boolean setMax(double[] parent, double[] child, int index) {
		if (parent[index] < child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Fix an IndexNode bounding box after a child has been removed
	 * @param indexNode
	 */
	public void adjustParentBoundingBox(Node indexNode, RelationshipType relationshipType) {
	    //make a default null bounding box
		//Envelope bbox = new Envelope();
		double [] bbox= {0,0,0,0};
		
		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			Node childNode = iterator.next().getEndNode();
			if (bbox == null) 
				bbox = getLeafNodeBoundingBox(childNode);
			else
			{
				//bbox.expandToInclude(getLeafNodeEnvelope(childNode));
				Double newX=(Double)childNode.getProperty("x");
				Double newY=(Double)childNode.getProperty("y");
				expandToInclude(bbox,getLeafNodeBoundingBox(childNode));
			}
		}
		indexNode.setProperty(PROP_BBOX, new double[] { bbox[0], bbox[2], bbox[1], bbox[3] });
	}
	
	
	
	public double [] getLeafNodeBoundingBox(Node geomNode) {
		return decodeLeafNodeBounds(geomNode);
	}
	
	
	private double[] decodeLeafNodeBounds(PropertyContainer container) {
	    double[] bbox = new double[]{0,0,0,0};
	    Object bboxProp = container.getProperty(PROP_BBOX);
		if (bboxProp instanceof Double[]) {
		    bbox = ArrayUtils.toPrimitive( (Double[])bboxProp);
		} else if (bboxProp instanceof double[]) {
	        bbox = (double[])bboxProp;
	    }
		// Bounding Box parameters: xmin, xmax, ymin, ymax
		return bbox;
	}
	
	
	
	
	
		
		
	
	
	private double[] envelopeToBBox(double[] bounds) {
        return new double[]{ bounds[0], bounds[2], bounds[1], bounds[3] };
    }
	
	
	public boolean addChild(Node parent, RelationshipType type, Node newChild) {
	    double[] childBBox = null;
	    if(type == SpatialRelationshipTypes.RTREE_REFERENCE) {
	        childBBox = envelopeToBBox(this.layer.getGeometryEncoder().decodeEnvelope(newChild));
	    } else {
	        childBBox = (double[]) newChild.getProperty(PROP_BBOX);
	    }
		parent.createRelationshipTo(newChild, type);
		return adjustParentBoundingBox(parent, childBBox);
	}
	
	
	/**
	   *  Returns <code>true</code> if this <code>bounding box</code> is a "null"
	   *  ebounding box
	   *
	   *@return    <code>true</code> if this <code>bounding box</code> is uninitialized
	   *      or is the envelope of the empty geometry.
	   */
	  public boolean isNull(double[] curBox) {
	    return curBox[1] < curBox[0];
	  }
	
	
	
	public double getAreaEnlargement(Node indexNode, Node geomRootNode) {
    	double[] before = getIndexNodeBoundingBox(indexNode);
    	
    	double[] after = getIndexNodeBoundingBox(indexNode);
    	//after.expandToInclude(before);
    	expandToInclude(after,before);
    	
    	return getArea(after) - getArea(before);
    }
	
	
	
	public double getArea(double[] bbox) {
		double width=Math.abs(bbox[1]-bbox[0]);
		double length=Math.abs(bbox[3]-bbox[2]);
		return width*length;
	}
	
	
    
	
	
	/**
	 * Adjust IndexNode bounding box according to the new child inserted
	 * @param parent IndexNode
	 * @param child geomNode inserted
	 * @return is bbox changed?
	 */
	public boolean adjustParentBoundingBox(Node parent, double[] childBBox) {
		if (!parent.hasProperty(PROP_BBOX)) {
			parent.setProperty(PROP_BBOX, new double[] { childBBox[0], childBBox[1], childBBox[2], childBBox[3] });
			return true;
		}
		
		double[] parentBBox = (double[]) parent.getProperty(PROP_BBOX);
		
		boolean valueChanged = setMin(parentBBox, childBBox, 0);
		valueChanged = setMin(parentBBox, childBBox, 1) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 2) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 3) || valueChanged;
		
		if (valueChanged) {
			parent.setProperty(PROP_BBOX, parentBBox);
		}
		
		return valueChanged;
	}
	
	
	public double[] getLayerBoundingBox() {
		return getIndexNodeBoundingBox(getIndexRoot());
	}
	
	
	
	
	/**
	 * The index nodes do NOT belong to the domain model, and as such need to
	 * use the indexes internal knowledge of the index tree and node structure
	 * for decoding the bounding box.
	 */
	public double[] getIndexNodeBoundingBox(Node indexNode) {
		if(indexNode ==null) indexNode = getIndexRoot();
		if (!indexNode.hasProperty(PROP_BBOX)) {
			System.err.println("Layer '" + layer.getName() + "' node[" + indexNode + "] has no bounding box property '" + PROP_BBOX + "'");
			return null;
		}
		return (double[])indexNode.getProperty(PROP_BBOX);
	}
	
	
	/**
	  * Tests if the given point lies in or on the bounding box.
	  *
	  *@param  x  the x-coordinate of the point which this <code>Envelope</code> is
	  *      being checked for containing
	  *@param  y  the y-coordinate of the point which this <code>Envelope</code> is
	  *      being checked for containing
	  *@return    <code>true</code> if <code>(x, y)</code> lies in the interior or
	  *      on the boundary of this <code>Envelope</code>.
	 */
	 public boolean coversBoundingBox(double [] curBox,double x, double y) {
	  	if (curBox[1] < curBox[0]) return false;
	    return x >= curBox[0] &&
	        x <= curBox[1] &&
	        y >= curBox[2] &&
	        y <= curBox[3];
	 }
}