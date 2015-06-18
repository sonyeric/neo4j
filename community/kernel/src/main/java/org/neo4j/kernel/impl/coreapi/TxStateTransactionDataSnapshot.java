/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.coreapi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.event.LabelEntry;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.helpers.ThisShouldNotHappenError;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyNotFoundException;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.txstate.ReadableTxState;
import org.neo4j.kernel.impl.api.RelationshipDataExtractor;
import org.neo4j.kernel.impl.api.state.NodeState;
import org.neo4j.kernel.impl.api.state.RelationshipState;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.api.store.StoreStatement;
import org.neo4j.kernel.impl.core.NodeProxy;
import org.neo4j.kernel.impl.core.RelationshipProxy;
import org.neo4j.kernel.impl.core.RelationshipProxy.RelationshipActions;
import org.neo4j.kernel.impl.util.diffsets.ReadableDiffSets;

/**
 * Transform for {@link org.neo4j.kernel.api.txstate.ReadableTxState} to make it accessible as {@link TransactionData}.
 */
public class TxStateTransactionDataSnapshot implements TransactionData
{
    private final ReadableTxState state;
    private final NodeProxy.NodeActions nodeActions;
    private final StoreStatement storeStatement;
    private final RelationshipActions relationshipActions;
    private final RelationshipDataExtractor relationshipData = new RelationshipDataExtractor();

    private final Collection<PropertyEntry<Node>> assignedNodeProperties = new ArrayList<>();
    private final Collection<PropertyEntry<Relationship>> assignedRelationshipProperties = new ArrayList<>();
    private final Collection<LabelEntry> assignedLabels = new ArrayList<>();

    private final Collection<PropertyEntry<Node>> removedNodeProperties = new ArrayList<>();
    private final Collection<PropertyEntry<Relationship>> removedRelationshipProperties = new ArrayList<>();
    private final Collection<LabelEntry> removedLabels = new ArrayList<>();

    public TxStateTransactionDataSnapshot(
            ReadableTxState state,
            NodeProxy.NodeActions nodeActions, RelationshipProxy.RelationshipActions relationshipActions,
            StoreReadLayer storeReadLayer)
    {
        this.state = state;
        this.nodeActions = nodeActions;
        this.relationshipActions = relationshipActions;
        this.storeStatement = storeReadLayer.acquireStatement();

        // Load changes that require store access eagerly, because we won't have access to the after-state
        // after the tx has been committed.
        takeSnapshot( state, storeReadLayer );

        storeStatement.close();
    }

    @Override
    public Iterable<Node> createdNodes()
    {
        return map2Nodes( state.addedAndRemovedNodes().getAdded() );
    }

    @Override
    public Iterable<Node> deletedNodes()
    {
        return map2Nodes( state.addedAndRemovedNodes().getRemoved() );
    }

    @Override
    public Iterable<Relationship> createdRelationships()
    {
        return map2Rels( state.addedAndRemovedRelationships().getAdded() );
    }

    @Override
    public Iterable<Relationship> deletedRelationships()
    {
        return map2Rels( state.addedAndRemovedRelationships().getRemoved() );
    }

    @Override
    public boolean isDeleted( Node node )
    {
        return state.nodeIsDeletedInThisTx( node.getId() );
    }

    @Override
    public boolean isDeleted( Relationship relationship )
    {
        return state.relationshipIsDeletedInThisTx( relationship.getId() );
    }

    @Override
    public Iterable<PropertyEntry<Node>> assignedNodeProperties()
    {
        return assignedNodeProperties;
    }

    @Override
    public Iterable<PropertyEntry<Node>> removedNodeProperties()
    {
        return removedNodeProperties;
    }

    @Override
    public Iterable<PropertyEntry<Relationship>> assignedRelationshipProperties()
    {
        return assignedRelationshipProperties;
    }

    @Override
    public Iterable<PropertyEntry<Relationship>> removedRelationshipProperties()
    {
        return removedRelationshipProperties;
    }

    @Override
    public Iterable<LabelEntry> removedLabels()
    {
        return removedLabels;
    }

    @Override
    public Iterable<LabelEntry> assignedLabels()
    {
        return assignedLabels;
    }

    private void takeSnapshot( ReadableTxState state, StoreReadLayer storeReadLayer )
    {
        try
        {
            for ( Long nodeId : state.addedAndRemovedNodes().getRemoved() )
            {
                Iterator<DefinedProperty> props = storeReadLayer.nodeGetAllProperties( storeStatement, nodeId );
                while ( props.hasNext() )
                {
                    DefinedProperty prop = props.next();
                    removedNodeProperties.add( new NodePropertyEntryView( nodeId,
                            storeReadLayer.propertyKeyGetName( prop.propertyKeyId() ), null, prop.value() ) );
                }

                PrimitiveIntIterator labels = storeReadLayer.nodeGetLabels( storeStatement, nodeId );
                while ( labels.hasNext() )
                {
                    removedLabels.add( new LabelEntryView( nodeId, storeReadLayer.labelGetName( labels.next() ) ) );
                }

            }
            for ( Long relId : state.addedAndRemovedRelationships().getRemoved() )
            {
                Relationship relationship = relationship( relId );
                Iterator<DefinedProperty> props = storeReadLayer.relationshipGetAllProperties(storeStatement , relId );
                while(props.hasNext())
                {
                    DefinedProperty prop = props.next();
                    removedRelationshipProperties.add( new RelationshipPropertyEntryView( relationship,
                            storeReadLayer.propertyKeyGetName( prop.propertyKeyId() ), null, prop.value() ) );
                }
            }
            for ( NodeState nodeState : state.modifiedNodes() )
            {
                Iterator<DefinedProperty> added = nodeState.addedAndChangedProperties();
                while ( added.hasNext() )
                {
                    DefinedProperty property = added.next();
                    assignedNodeProperties.add( new NodePropertyEntryView( nodeState.getId(),
                            storeReadLayer.propertyKeyGetName( property.propertyKeyId() ), property.value(),
                            committedValue( storeReadLayer, nodeState, property.propertyKeyId() ) ) );
                }
                Iterator<Integer> removed = nodeState.removedProperties();
                while ( removed.hasNext() )
                {
                    Integer property = removed.next();
                    removedNodeProperties.add( new NodePropertyEntryView( nodeState.getId(),
                            storeReadLayer.propertyKeyGetName( property ), null,
                            committedValue( storeReadLayer, nodeState, property ) ) );
                }
                ReadableDiffSets<Integer> labels = nodeState.labelDiffSets();
                for ( Integer label : labels.getAdded() )
                {
                    assignedLabels.add( new LabelEntryView( nodeState.getId(), storeReadLayer.labelGetName( label ) ) );
                }
                for ( Integer label : labels.getRemoved() )
                {
                    removedLabels.add( new LabelEntryView( nodeState.getId(), storeReadLayer.labelGetName( label ) ) );
                }
            }
            for ( RelationshipState relState : state.modifiedRelationships() )
            {
                Relationship relationship = relationship( relState.getId() );
                Iterator<DefinedProperty> added = relState.addedAndChangedProperties();
                while ( added.hasNext() )
                {
                    DefinedProperty property = added.next();
                    assignedRelationshipProperties.add( new RelationshipPropertyEntryView( relationship,
                            storeReadLayer.propertyKeyGetName( property.propertyKeyId() ), property.value(),
                            committedValue( storeReadLayer, relState, property.propertyKeyId() ) ) );
                }
                Iterator<Integer> removed = relState.removedProperties();
                while ( removed.hasNext() )
                {
                    Integer property = removed.next();
                    removedRelationshipProperties.add( new RelationshipPropertyEntryView( relationship,
                            storeReadLayer.propertyKeyGetName( property ), null,
                            committedValue( storeReadLayer, relState, property ) ) );
                }
            }
        }
        catch ( EntityNotFoundException | PropertyKeyIdNotFoundKernelException | LabelNotFoundKernelException e )
        {
            throw new ThisShouldNotHappenError( "Jake", "An entity that does not exist was modified.", e );
        }
    }

    private Relationship relationship( long relId )
    {
        state.relationshipVisit( relId, relationshipData );
        return new RelationshipProxy( relationshipActions, relId,
                relationshipData.startNode(), relationshipData.type(), relationshipData.endNode() );
    }

    private Iterable<Node> map2Nodes( Iterable<Long> added )
    {
        return new IterableWrapper<Node,Long>( added )
        {
            @Override
            protected Node underlyingObjectToObject( Long id )
            {
                return new NodeProxy( nodeActions, id );
            }
        };
    }

    private Iterable<Relationship> map2Rels( Iterable<Long> ids )
    {
        return new IterableWrapper<Relationship,Long>( ids )
        {
            @Override
            protected Relationship underlyingObjectToObject( Long id )
            {
                return relationship( id );
            }
        };
    }

    private Object committedValue( StoreReadLayer storeReadLayer, NodeState nodeState, int property )
    {
        try
        {
            if ( state.nodeIsAddedInThisTx( nodeState.getId() ) )
            {
                return null;
            }
            return storeReadLayer.nodeGetProperty( storeStatement, nodeState.getId(), property ).value();
        }
        catch ( EntityNotFoundException | PropertyNotFoundException e )
        {
            return null;
        }
    }

    private Object committedValue( StoreReadLayer storeReadLayer, RelationshipState relState, int property )
    {
        try
        {
            if ( state.relationshipIsAddedInThisTx( relState.getId() ) )
            {
                return null;
            }
            return storeReadLayer.relationshipGetProperty( storeStatement, relState.getId(), property ).value();
        }
        catch ( EntityNotFoundException | PropertyNotFoundException e )
        {
            return null;
        }
    }

    private class NodePropertyEntryView implements PropertyEntry<Node>
    {
        private final long nodeId;
        private final String key;
        private final Object newValue;
        private final Object oldValue;

        public NodePropertyEntryView( long nodeId, String key, Object newValue, Object oldValue )
        {
            this.nodeId = nodeId;
            this.key = key;
            this.newValue = newValue;
            this.oldValue = oldValue;
        }

        @Override
        public Node entity()
        {
            return new NodeProxy( nodeActions, nodeId );
        }

        @Override
        public String key()
        {
            return key;
        }

        @Override
        public Object previouslyCommitedValue()
        {
            return oldValue;
        }

        @Override
        public Object value()
        {
            if ( newValue == null )
            {
                throw new IllegalStateException( "This property has been removed, it has no value anymore." );
            }
            return newValue;
        }

        @Override
        public String toString()
        {
            return "NodePropertyEntryView{" +
                    "nodeId=" + nodeId +
                    ", key='" + key + '\'' +
                    ", newValue=" + newValue +
                    ", oldValue=" + oldValue +
                    '}';
        }
    }

    private class RelationshipPropertyEntryView implements PropertyEntry<Relationship>
    {
        private final Relationship relationship;
        private final String key;
        private final Object newValue;
        private final Object oldValue;

        public RelationshipPropertyEntryView( Relationship relationship,
                String key, Object newValue, Object oldValue )
        {
            this.relationship = relationship;
            this.key = key;
            this.newValue = newValue;
            this.oldValue = oldValue;
        }

        @Override
        public Relationship entity()
        {
            return relationship;
        }

        @Override
        public String key()
        {
            return key;
        }

        @Override
        public Object previouslyCommitedValue()
        {
            return oldValue;
        }

        @Override
        public Object value()
        {
            if ( newValue == null )
            {
                throw new IllegalStateException( "This property has been removed, it has no value anymore." );
            }
            return newValue;
        }

        @Override
        public String toString()
        {
            return "RelationshipPropertyEntryView{" +
                    "relId=" + relationship.getId() +
                    ", key='" + key + '\'' +
                    ", newValue=" + newValue +
                    ", oldValue=" + oldValue +
                    '}';
        }
    }

    private class LabelEntryView implements LabelEntry
    {
        private final long nodeId;
        private final Label label;

        public LabelEntryView( long nodeId, String labelName )
        {
            this.nodeId = nodeId;
            this.label = DynamicLabel.label( labelName );
        }

        @Override
        public Label label()
        {
            return label;
        }

        @Override
        public Node node()
        {
            return new NodeProxy( nodeActions, nodeId );
        }

        @Override
        public String toString()
        {
            return "LabelEntryView{" +
                    "nodeId=" + nodeId +
                    ", label=" + label +
                    '}';
        }
    }
}
