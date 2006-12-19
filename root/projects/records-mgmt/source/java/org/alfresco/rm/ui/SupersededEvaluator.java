/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.rm.ui;

import org.alfresco.rm.RecordsManagementModel;
import org.alfresco.web.bean.repository.Node;


/**
 * @author Roy Wetherall
 */
public class SupersededEvaluator extends BaseEvaluator
{
    public boolean evaluate(Node node)
    {
        boolean result = false;
        if (node.hasAspect(RecordsManagementModel.ASPECT_RECORD) == true &&
            node.hasAspect(RecordsManagementModel.ASPECT_CUTOFF) == false &&
            node.hasAspect(RecordsManagementModel.ASPECT_HELD) == false &&
            node.hasAspect(RecordsManagementModel.ASPECT_SUPERSEDED) == false &&
            isRecordsManager() == true)
        {
            result = true;            
        }
        return result;
    }
}
