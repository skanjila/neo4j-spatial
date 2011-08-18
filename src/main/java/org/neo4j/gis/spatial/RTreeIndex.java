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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.neo4j.gis.spatial.query.SearchAll;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Traverser.Order;



/**
 * The RTreeIndex is the first and still standard index for Neo4j Spatial. It
 * implements both SpatialIndexReader and SpatialIndexWriter for read and write
 * support. In addition it implements SpatialTreeIndex which allows it to be
 * wrapped with modifying search functions to thatcustom classes can be used to
 * perform filterintg searches on the tree.
 * 
 * @author Davide Savazzi
 */
public class RTreeIndex implements SpatialTreeIndex, SpatialIndexWriter, Constants {

	// Constructor
	
	public RTreeIndex(GraphDatabaseService database, Layer layer) {
		this(database, layer, 100, 51);
	}
	
	public RTreeIndex(GraphDatabaseService database, Layer layer, int maxNodeReferences, int minNodeReferences) {
		this.database = database;
		this.layer = layer;
		this.maxNodeReferences = maxNodeReferences;
		this.minNodeReferences = minNodeReferences;
		this.boundingBox=new BoundingBox();
		
		initIndexRoot();
		initIndexMetadata();
	}
	
	
	// Public methods
	
	public void add(Node geomNode) {
		// initialize the search with root
		Node parent = getIndexRoot();
		
		// choose a path down to a leaf
		while (!nodeIsLeaf(parent)) {
			parent = chooseSubTree(parent, geomNode);
		}
		
		if (countChildren(parent, SpatialRelationshipTypes.RTREE_REFERENCE) == maxNodeReferences) {
			insertInLeaf(parent, geomNode);
			splitAndAdjustPathBoundingBox(parent);
		} else {
			if (insertInLeaf(parent, geomNode)) {
				// bbox enlargement needed
				boundingBox.adjustPathBoundingBox(parent);							
			}
		}
		countSaved = false;
		totalGeometryCount ++;
	}
	
	public void remove(long geomNodeId, boolean deleteGeomNode) {
		Node geomNode = database.getNodeById(geomNodeId);
		
		// be sure geomNode is inside this RTree
		Node indexNode = findLeafContainingGeometryNode(geomNode);
		
		// remove the entry 
		geomNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).delete();
		if (deleteGeomNode) deleteNode(geomNode);
		
		// reorganize the tree if needed
		if (getIndexNodeParent(indexNode) != null && countChildren(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE) < minNodeReferences) {
			// indexNode is not the root and contain less than the minimum number of entries
			// tree needs reorganization
			
			// find the parent that must be deleted (its children < minNodeReferences) nearest to the root
			Node lastParentNodeToDelete = findIndexNodeToDeleteNearestToRoot(indexNode);
			
			// find all geomNodes in the subtree
			SearchAll search = new SearchAll();
			search.setLayer(layer);
			visit(search, lastParentNodeToDelete);
			List<SpatialDatabaseRecord> orphanedGeometryNodes = search.getResults();

			// remove all geomNode in the subtree
			for (SpatialDatabaseRecord orphan : orphanedGeometryNodes) {
				orphan.getGeomNode().getSingleRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).delete();
			}
			
			deleteRecursivelyEmptySubtree(lastParentNodeToDelete);

			// adjust tree
			boundingBox.adjustParentBoundingBox(getIndexNodeParent(lastParentNodeToDelete), SpatialRelationshipTypes.RTREE_CHILD);
			boundingBox.adjustPathBoundingBox(getIndexNodeParent(lastParentNodeToDelete));
			
			// add orphaned geomNodes
			for (SpatialDatabaseRecord orphan : orphanedGeometryNodes) {
				add(orphan.getGeomNode());
			}			
		} else {
			// indexNode is root or contains more than the minimum number of geomNode references
			boundingBox.adjustParentBoundingBox(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE);
			boundingBox.adjustPathBoundingBox(indexNode);
		}
		countSaved = false;
		totalGeometryCount --;
	}
	
	
	
    
	
	
	/**
	 * Adjust IndexNode bounding box according to the new child inserted
	 * @param parent IndexNode
	 * @param child geomNode inserted
	 * @return is bbox changed?
	 */
	private boolean adjustParentBoundingBox(Node parent, double[] childBBox) {
		if (!parent.hasProperty(PROP_BBOX)) {
			parent.setProperty(PROP_BBOX, new double[] { childBBox[0], childBBox[1], childBBox[2], childBBox[3] });
			return true;
		}
		
		double[] parentBBox = (double[]) parent.getProperty(PROP_BBOX);
		
		boolean valueChanged = boundingBox.setMin(parentBBox, childBBox, 0);
		valueChanged = boundingBox.setMin(parentBBox, childBBox, 1) || valueChanged;
		valueChanged = boundingBox.setMax(parentBBox, childBBox, 2) || valueChanged;
		valueChanged = boundingBox.setMax(parentBBox, childBBox, 3) || valueChanged;
		
		if (valueChanged) {
			parent.setProperty(PROP_BBOX, parentBBox);
		}
		
		return valueChanged;
	}
	
	
	
	
	public void removeAll(final boolean deleteGeomNodes, final Listener monitor) {
		Node indexRoot = getIndexRoot();
		
		monitor.begin(count());
		try {
			// delete all geometry nodes
			visitInTx(new SpatialIndexVisitor() {
				public boolean needsToVisit(double[] indexNodeEnvelope) {
					return true;
				}
	
				public void onIndexReference(Node geomNode) {
					geomNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).delete();
					if (deleteGeomNodes) deleteNode(geomNode);
					
					monitor.worked(1);
				}
			}, indexRoot.getId());	
		} finally {
			monitor.done();
		}

		Transaction tx = database.beginTx();
		try {
			// delete index root relationship
			indexRoot.getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.INCOMING).delete();
			
			// delete tree
			deleteRecursivelyEmptySubtree(indexRoot);
			
			// delete tree metadata
			Relationship metadataNodeRelationship = layer.getLayerNode().getSingleRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING);
			Node metadataNode = metadataNodeRelationship.getEndNode();
			metadataNodeRelationship.delete();
			metadataNode.delete();
		
			tx.success();
		} finally {
			tx.finish();
		}		
		countSaved = false;
		totalGeometryCount = 0;
	}
	
    public void clear(final Listener monitor) {
        removeAll(false, new NullListener());
        Transaction tx = database.beginTx();
        try {
            initIndexRoot();
            initIndexMetadata();
            tx.success();
        } finally {
            tx.finish();
        }
    }
	
	public int count() {
		saveCount();
		return totalGeometryCount;
	}

	public boolean isEmpty() {
		Node indexRoot = getIndexRoot();
		return !indexRoot.hasProperty(PROP_BBOX);
	}
	
	public SpatialDatabaseRecord get(Long geomNodeId) {
		Node geomNode = database.getNodeById(geomNodeId);			
		// be sure geomNode is inside this RTree
		findLeafContainingGeometryNode(geomNode);

		return new SpatialDatabaseRecord(layer,geomNode);
	}
	
	public List<SpatialDatabaseRecord> get(Set<Long> geomNodeIds) {
		List<SpatialDatabaseRecord> results = new ArrayList<SpatialDatabaseRecord>();
		for (Long geomNodeId : geomNodeIds) {
			results.add(get(geomNodeId));
		}
		return results;
	}

	public void executeSearch(Search search) {
		if (isEmpty()) return;
		saveCount();
		
		search.setLayer(layer);
		visit(search, getIndexRoot());
	}
	
	public void warmUp() {
		visit(new WarmUpVisitor(), getIndexRoot());
	}
	
	
	// Private methods
	
	
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
	
	
	public void visit(SpatialIndexVisitor visitor, Node indexNode) {
		if (!visitor.needsToVisit(getIndexNodeBoundingBox(indexNode))) return;
		
		if (indexNode.hasRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			// Node is not a leaf
			for (Relationship rel : indexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
				Node child = rel.getEndNode();
				// collect children results
				visit(visitor, child);
			}
		} else if (indexNode.hasRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
			// Node is a leaf
			for (Relationship rel : indexNode.getRelationships(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
				visitor.onIndexReference(rel.getEndNode());
			}
		}
	}
	
	private void visitInTx(SpatialIndexVisitor visitor, Long indexNodeId) {
        Node indexNode = database.getNodeById(indexNodeId);
        if(!visitor.needsToVisit(getIndexNodeBoundingBox(indexNode))) return;
		
		if (indexNode.hasRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			// Node is not a leaf
			
			// collect children
			List<Long> children = new ArrayList<Long>();
            for (Relationship rel : indexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
                children.add(rel.getEndNode().getId());
            }

			// visit children
			for (Long child : children) {
				visitInTx(visitor, child);	
			}
		} else if (indexNode.hasRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
			// Node is a leaf
			Transaction tx = database.beginTx();
			try {
				for (Relationship rel : indexNode.getRelationships(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
					visitor.onIndexReference(rel.getEndNode());
				}
			
				tx.success();
			} finally {
				tx.finish();
			}
		}
	}
	
	private Node getMetadataNode() {
		if (metadataNode == null) {
			metadataNode = layer.getLayerNode().getSingleRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING)
					.getEndNode();
		}
		return metadataNode;
	}

	public void finalize() {
		saveCount();
	}

	/**
	 * Save the geometry count to the database if it has not been saved yet.
	 * However, if the count is zero, first do an exhaustive search of the tree
	 * and count everything before saving it.
	 */
	private void saveCount() {
		if (totalGeometryCount == 0) {
			RecordCounter counter = new RecordCounter();
			visit(counter, getIndexRoot());
			totalGeometryCount = counter.getResult();
			countSaved = false;
		}
		if (!countSaved) {
			Transaction tx = database.beginTx();
			try {
				getMetadataNode().setProperty("totalGeometryCount", totalGeometryCount);
				countSaved = true;
				tx.success();
			} finally {
				tx.finish();
			}
		}
	}
	
	private void initIndexMetadata() {
		Node layerNode = layer.getLayerNode();
		if (layerNode.hasRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING)) {
			// metadata already present
			metadataNode = layerNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING).getEndNode();
			
			maxNodeReferences = (Integer) metadataNode.getProperty("maxNodeReferences");
			minNodeReferences = (Integer) metadataNode.getProperty("minNodeReferences");
			if (countSaved) {
				totalGeometryCount = (Integer) metadataNode.getProperty("totalGeometryCount", 0);
			}
		} else {
			// metadata initialization
			metadataNode = database.createNode();
			layerNode.createRelationshipTo(metadataNode, SpatialRelationshipTypes.RTREE_METADATA);
			
			metadataNode.setProperty("maxNodeReferences", maxNodeReferences);
			metadataNode.setProperty("minNodeReferences", minNodeReferences);
		}
		saveCount();
	}

	private void initIndexRoot() {
		Node layerNode = layer.getLayerNode();
		if (!layerNode.hasRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING)) {
			// index initialization
			Node root = database.createNode();
			layerNode.createRelationshipTo(root, SpatialRelationshipTypes.RTREE_ROOT);
		}
	}
	
	public Node getIndexRoot() {
		return layer.getLayerNode().getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).getEndNode();
	}
	
	private boolean nodeIsLeaf(Node node) {
		return !node.hasRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
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
	 public boolean coversBoundingBox(double [] curBbox,double x, double y) {
	  	if (curBbox[1] < curBbox[0]) return false;
	    return x >= curBbox[0] &&
	        x <= curBbox[1] &&
	        y >= curBbox[2] &&
	        y <= curBbox[3];
	 }
	
	
	private Node chooseSubTree(Node parentIndexNode, Node geomRootNode) {
		// children that can contain the new geometry
		List<Node> indexNodes = new ArrayList<Node>();
		
		// pick the child that contains the new geometry bounding box		
		Iterable<Relationship> relationships = parentIndexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);		
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			Double x = (Double)geomRootNode.getProperty("x");
			Double y = (Double)geomRootNode.getProperty("y");
			if (coversBoundingBox(getIndexNodeBoundingBox(indexNode),x.doubleValue(),y.doubleValue())) {
				indexNodes.add(indexNode);
			}
		}

		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		}
		
		// pick the child that needs the minimum enlargement to include the new geometry
		double minimumEnlargement = Double.POSITIVE_INFINITY;
		relationships = parentIndexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			double enlargementNeeded = getAreaEnlargement(indexNode, geomRootNode);

			if (enlargementNeeded < minimumEnlargement) {
				indexNodes.clear();
				indexNodes.add(indexNode);
				minimumEnlargement = enlargementNeeded;
			} else if (enlargementNeeded == minimumEnlargement) {
				indexNodes.add(indexNode);				
			}
		}
		
		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		} else {
			// this shouldn't happen
			throw new SpatialDatabaseException("No IndexNode found for new geometry");
		}
	}

	private double getAreaEnlargement(Node indexNode, Node geomRootNode) {
    	double[] before = getIndexNodeBoundingBox(indexNode);
    	
    	double[] after = getIndexNodeBoundingBox(indexNode);
    	//after.expandToInclude(before);
    	boundingBox.expandToInclude(after,before);
    	
    	return getArea(after) - getArea(before);
    }
	
	
	
	public double getArea(double[] bbox) {
		double width=Math.abs(bbox[1]-bbox[0]);
		double length=Math.abs(bbox[3]-bbox[2]);
		return width*length;
	}
	
	
	public double getArea(Node node) {
		return getArea(getLeafNodeBoundingBox(node));
	}
	
	
	
    
	private Node chooseIndexNodeWithSmallestArea(List<Node> indexNodes) {
		Node result = null;
		double smallestArea = -1;

		for (Node indexNode : indexNodes) {
			double area = getArea(indexNode);
			if (result == null || area < smallestArea) {
				result = indexNode;
				smallestArea = area;
			}
		}
		
		return result;
	}

	private int countChildren(Node indexNode, RelationshipType relationshipType) {
		int counter = 0;
		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			iterator.next();
			counter++;
		}
		return counter;
	}
	
	/**
	 * @return is enlargement needed?
	 */
	private boolean insertInLeaf(Node indexNode, Node geomRootNode) {
		return addChild(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE, geomRootNode);
	}

	private void splitAndAdjustPathBoundingBox(Node indexNode) {
		// create a new node and distribute the entries
		Node newIndexNode = quadraticSplit(indexNode);
		Node parent = getIndexNodeParent(indexNode);
		if (parent == null) {
			// if indexNode is the root
			createNewRoot(indexNode, newIndexNode);
		} else {
			adjustParentBoundingBox(parent, (double[]) indexNode.getProperty(PROP_BBOX));
			
			addChild(parent, SpatialRelationshipTypes.RTREE_CHILD, newIndexNode);

			if (countChildren(parent, SpatialRelationshipTypes.RTREE_CHILD) > maxNodeReferences) {
				splitAndAdjustPathBoundingBox(parent);
			} else {
				boundingBox.adjustPathBoundingBox(parent);
			}
		}
	}

	private Node quadraticSplit(Node indexNode) {
		if (nodeIsLeaf(indexNode)) return boundingBox.quadraticSplit(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE);
		else return boundingBox.quadraticSplit(indexNode, SpatialRelationshipTypes.RTREE_CHILD);
	}

	

	private void createNewRoot(Node oldRoot, Node newIndexNode) {
		Node newRoot = database.createNode();
		addChild(newRoot, SpatialRelationshipTypes.RTREE_CHILD, oldRoot);
		addChild(newRoot, SpatialRelationshipTypes.RTREE_CHILD, newIndexNode);
		
		Node layerNode = layer.getLayerNode();
		layerNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).delete();
		layerNode.createRelationshipTo(newRoot, SpatialRelationshipTypes.RTREE_ROOT);
	}

    private double[] envelopeToBBox(double[] bounds) {
        return new double[]{ bounds[0], bounds[2], bounds[1], bounds[3] };
    }


	private boolean addChild(Node parent, RelationshipType type, Node newChild) {
	    double[] childBBox = null;
	    if(type == SpatialRelationshipTypes.RTREE_REFERENCE) {
	        childBBox = envelopeToBBox(this.layer.getGeometryEncoder().decodeEnvelope(newChild));
	    } else {
	        childBBox = (double[]) newChild.getProperty(PROP_BBOX);
	    }
		parent.createRelationshipTo(newChild, type);
		return adjustParentBoundingBox(parent, childBBox);
	}
	
	
	
	
	private Node getIndexNodeParent(Node indexNode) {
		Relationship relationship = indexNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
		if (relationship == null) return null;
		else return relationship.getStartNode();
	}	


	private Node findIndexNodeToDeleteNearestToRoot(Node indexNode) {
		Node indexNodeParent = getIndexNodeParent(indexNode);
		
		if (getIndexNodeParent(indexNodeParent) != null && countChildren(indexNodeParent, SpatialRelationshipTypes.RTREE_CHILD) == minNodeReferences) {
			// indexNodeParent is not the root and will contain less than the minimum number of entries
			return findIndexNodeToDeleteNearestToRoot(indexNodeParent);
		} else {
			return indexNode;
		}
	}
	
	private void deleteRecursivelyEmptySubtree(Node indexNode) {
		for (Relationship relationship : indexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			deleteRecursivelyEmptySubtree(relationship.getEndNode());
		}
		
		Relationship relationshipWithFather = indexNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
		// the following check is needed because rootNode doesn't have this relationship
		if (relationshipWithFather != null) {
			relationshipWithFather.delete();
		}
		indexNode.delete();
	}
	
	private Node findLeafContainingGeometryNode(Node geomNode) {
		if (!geomNode.hasRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING)) {
			throw new SpatialDatabaseException("GeometryNode not indexed with an RTree: " + geomNode.getId());
		}

		Node indexNodeLeaf = geomNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).getStartNode();		
		
		// check if geometryNode is indexed in this rtre
		Node root = null;
		Node child = indexNodeLeaf;
		while (root == null) {
			Node parent = getIndexNodeParent(child);
			if (parent == null) root = child;
			else child = parent;
		}
		
		if (root.getId() != getIndexRoot().getId()) {
			throw new SpatialDatabaseException("GeometryNode not indexed in this RTree: " + geomNode.getId());
		} else {
			return indexNodeLeaf;
		}
	}
	
	private void deleteNode(Node node) {
		for (Relationship r : node.getRelationships()) {
			r.delete();
		}
		node.delete();
	}	
	
    

	
	// Attributes
	
	private GraphDatabaseService database;
	private Layer layer;
	private int maxNodeReferences;
	private int minNodeReferences;
	private Node metadataNode;
	private int totalGeometryCount;
	private boolean countSaved = false;
	private BoundingBox boundingBox = null;

	
	// Private classes

	static class RecordCounter implements SpatialIndexVisitor {	
		
		public void onIndexReference(Node geomNode) { count++; }
		
		public int getResult() { return count; }
		
		private int count = 0;

		@Override
		public boolean needsToVisit(double[] indexNodeEnvelope) {
			// TODO Auto-generated method stub
			return false;
		}
	}

	
	
	class BoundingBox {
		/**
	     * Create a bounding box encompassing the two bounding boxes passed in.
	     */	
		private double[] createBoundingBox(double[] e, double[] e1) {
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
	    public void expandToInclude(double [] curBbox,double [] otherBbox) {
	        if (otherBbox[1]<otherBbox[0]) {
	          return;
	        }
	        if (curBbox[1]<curBbox[0]) {
	        	curBbox[0] = otherBbox[0];
	        	curBbox[1] = otherBbox[1];
	        	curBbox[2] = otherBbox[2];
	        	curBbox[3] = otherBbox[3];
	        }
	        else {
	          if (otherBbox[0] < curBbox[0]) {
	        	  curBbox[0] = otherBbox[0];
	          }
	          if (otherBbox[1] > curBbox[1]) {
	        	  curBbox[1] = otherBbox[1];
	          }
	          if (otherBbox[2] < curBbox[2]) {
	        	  curBbox[2] = otherBbox[2];
	          }
	          if (otherBbox[3] > curBbox[3]) {
	        	  curBbox[3] = otherBbox[3];
	          }
	        }
	    }
	    
	    
		
		private void adjustPathBoundingBox(Node indexNode) {
			Node parent = getIndexNodeParent(indexNode);
			if (parent != null) {
				if (boundingBox.adjustParentBoundingBox(parent, (double[]) indexNode.getProperty(PROP_BBOX))) {
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
		
		
		private Node quadraticSplit(Node indexNode, RelationshipType relationshipType) {
	 		List<Node> entries = new ArrayList<Node>();
			
			Iterable<Relationship> relationships = indexNode.getRelationships(relationshipType, Direction.OUTGOING);
			for (Relationship relationship : relationships) {
				entries.add(relationship.getEndNode());
				relationship.delete();
			}

			// pick two seed entries such that the dead space is maximal
			Node seed1 = null;
			Node seed2 = null;
			double worst = Double.NEGATIVE_INFINITY;
			for (Node e : entries) {
				double[] eEnvelope = getLeafNodeBoundingBox(e);
				for (Node e1 : entries) {
					double[] e1Envelope = getLeafNodeBoundingBox(e1);
					double deadSpace = getArea(createBoundingBox(eEnvelope, e1Envelope)) - getArea(eEnvelope) - getArea(e1Envelope);
					if (deadSpace > worst) {
						worst = deadSpace;
						seed1 = e;
						seed2 = e1;
					}
				}
			}
			
			List<Node> group1 = new ArrayList<Node>();
			group1.add(seed1);
			double[] group1envelope = getLeafNodeBoundingBox(seed1);
			
			List<Node> group2 = new ArrayList<Node>();
			group2.add(seed2);
			double[] group2envelope = getLeafNodeBoundingBox(seed2);
			
			entries.remove(seed1);
			entries.remove(seed2);
			while (entries.size() > 0) {
				// compute the cost of inserting each entry
				List<Node> bestGroup = null;
				double[] bestGroupEnvelope = null;
				Node bestEntry = null;
				double expansionMin = Double.POSITIVE_INFINITY;
				for (Node e : entries) {
					double[] nodeEnvelope = getLeafNodeBoundingBox(e);
					double expansion1 = getArea(createBoundingBox(nodeEnvelope, group1envelope)) - getArea(group1envelope);
					double expansion2 = getArea(createBoundingBox(nodeEnvelope, group2envelope)) - getArea(group2envelope);
							
					if (expansion1 < expansion2 && expansion1 < expansionMin) {
						bestGroup = group1;
						bestGroupEnvelope = group1envelope;
						bestEntry = e;
						expansionMin = expansion1;
					} else if (expansion2 < expansion1 && expansion2 < expansionMin) {
						bestGroup = group2;
						bestGroupEnvelope = group2envelope;					
						bestEntry = e;
						expansionMin = expansion2;					
					} else if (expansion1 == expansion2 && expansion1 < expansionMin) {
						// in case of equality choose the group with the smallest area
						if (getArea(group1envelope) < getArea(group2envelope)) {
							bestGroup = group1;
							bestGroupEnvelope = group1envelope; 
						} else {
							bestGroup = group2;
							bestGroupEnvelope = group2envelope; 
						}
						bestEntry = e;
						expansionMin = expansion1;					
					}
				}
				
				// insert the best candidate entry in the best group
				bestGroup.add(bestEntry);
				expandToInclude(bestGroupEnvelope,getLeafNodeBoundingBox(bestEntry));

				entries.remove(bestEntry);
				
				// each group must contain at least minNodeReferences entries.
				// if the group size added to the number of remaining entries is equal to minNodeReferences
				// just add them to the group
				
				if (group1.size() + entries.size() == minNodeReferences) {
					group1.addAll(entries);
					entries.clear();
				}
				
				if (group2.size() + entries.size() == minNodeReferences) {
					group2.addAll(entries);
					entries.clear();
				}
			}
			
			// reset bounding box and add new children
			indexNode.removeProperty(PROP_BBOX);
			for (Node node : group1) {
				addChild(indexNode, relationshipType, node);
			}

			// create new node from split
			Node newIndexNode = database.createNode();
			for (Node node: group2) {
				addChild(newIndexNode, relationshipType, node);
			}
			
			return newIndexNode;
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
		
		/**
		 * Adjust IndexNode bounding box according to the new child inserted
		 * @param parent IndexNode
		 * @param child geomNode inserted
		 * @return is bbox changed?
		 */
		private boolean adjustParentBoundingBox(Node parent, double[] childBBox) {
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
		
	}
	
	
	class WarmUpVisitor implements SpatialIndexVisitor {
		
		public boolean needsToVisit(double[] indexNodeEnvelope) { return true; }	
		
		public void onIndexReference(Node geomNode) { }	
	}

	/**
	 * In order to wrap one iterable or iterator in another that converts the
	 * objects from one type to another without loading all into memory, we need
	 * to use this ugly java-magic. Man, I miss Ruby right now!
	 * 
	 * @author craig
	 * @since 1.0.0
	 */
	private class IndexNodeToGeometryNodeIterable implements Iterable<Node> {
		private Iterator<Node> allIndexNodeIterator;

		private class GeometryNodeIterator implements Iterator<Node> {
			Iterator<Node> geometryNodeIterator = null;

			public boolean hasNext() {
				checkGeometryNodeIterator();
				return geometryNodeIterator != null && geometryNodeIterator.hasNext();
			}

			public Node next() {
				checkGeometryNodeIterator();
				return geometryNodeIterator == null ? null : geometryNodeIterator.next();
			}

			private void checkGeometryNodeIterator() {
				while ((geometryNodeIterator == null || !geometryNodeIterator.hasNext()) && allIndexNodeIterator.hasNext()) {
					geometryNodeIterator = allIndexNodeIterator.next().traverse(Order.DEPTH_FIRST, StopEvaluator.DEPTH_ONE,
					        ReturnableEvaluator.ALL_BUT_START_NODE, SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)
					        .iterator();
				}
			}

			public void remove() {
			}

		}

		public IndexNodeToGeometryNodeIterable(Iterable<Node> allIndexNodes) {
			this.allIndexNodeIterator = allIndexNodes.iterator();
		}

		public Iterator<Node> iterator() {
			return new GeometryNodeIterator();
		}

	}

	public Iterable<Node> getAllIndexNodes() {
		return getIndexRoot().traverse(Order.BREADTH_FIRST, StopEvaluator.END_OF_GRAPH, ReturnableEvaluator.ALL_BUT_START_NODE,
		        SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
	}

	public Iterable<Node> getAllGeometryNodes() {
		return new IndexNodeToGeometryNodeIterable(getAllIndexNodes());
	}

	private static String arrayString(double[] test) {
		StringBuffer sb = new StringBuffer();
		for (double d : test)
			addToArrayString(sb, d);
		sb.append("]");
		return sb.toString();
	}

	private static void addToArrayString(StringBuffer sb, Object obj) {
		if (sb.length() == 0) {
			sb.append("[");
		} else {
			sb.append(",");
		}
		sb.append(obj);
	}

	public void debugIndexTree() {
		printTree(getIndexRoot(), 0);
	}

	private void printTree(Node root, int depth) {
		StringBuffer tab = new StringBuffer();
		for (int i = 0; i < depth; i++) {
			tab.append("  ");
		}
		if (root.hasProperty(PROP_BBOX)) {
			System.out.println(tab.toString() + "INDEX: " + root + " BBOX[" + arrayString((double[]) root.getProperty(PROP_BBOX))
					+ "]");
		} else {
			System.out.println(tab.toString() + "INDEX: " + root);
		}
		StringBuffer data = new StringBuffer();
		for (Relationship rel : root.getRelationships(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
			if (data.length() > 0) {
				data.append(", ");
			} else {
				data.append("DATA: ");
			}
			data.append(rel.getEndNode().toString());
		}
		if (data.length() > 0) {
			System.out.println("  " + tab + data);
		}
		for (Relationship rel : root.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			printTree(rel.getEndNode(), depth + 1);
		}
	}

}