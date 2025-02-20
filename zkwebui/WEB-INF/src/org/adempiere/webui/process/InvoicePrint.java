/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.adempiere.webui.process;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.*;

import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.window.SimplePDFViewer;
import org.compiere.model.*;
import org.compiere.print.*;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.*;
import org.spin.queue.notification.DefaultNotifier;
import org.spin.queue.util.QueueLoader;
import org.zkoss.zk.ui.util.Clients;

/**
 *	Print Invoices on Paperor send PDFs
 *
 * 	@author 	Jorg Janke
 * 	@version 	$Id: InvoicePrint.java,v 1.2 2006/07/30 00:51:02 jjanke Exp $
 */
public class InvoicePrint extends SvrProcess
{
	/**	Mail PDF			*/
	private boolean		p_EMailPDF = false;
	/** Mail Template		*/
	private int			p_R_MailText_ID = 0;
	
	private Timestamp	m_dateInvoiced_From = null;
	private Timestamp	m_dateInvoiced_To = null;
	private int			m_C_BPartner_ID = 0;
	private int			m_C_Invoice_ID = 0;
	private String		m_DocumentNo_From = null;
	private String		m_DocumentNo_To = null;

	/**
	 *  Prepare - e.g., get Parameters.
	 */
	protected void prepare()
	{
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;
			else if (name.equals("DateInvoiced"))
			{
				m_dateInvoiced_From = ((Timestamp)para[i].getParameter());
				m_dateInvoiced_To = ((Timestamp)para[i].getParameter_To());
			}
			else if (name.equals("EMailPDF"))
				p_EMailPDF = "Y".equals(para[i].getParameter());
			else if (name.equals("R_MailText_ID"))
				p_R_MailText_ID = para[i].getParameterAsInt();
			else if (name.equals("C_BPartner_ID"))
				m_C_BPartner_ID = para[i].getParameterAsInt();
			else if (name.equals("C_Invoice_ID"))
				m_C_Invoice_ID = para[i].getParameterAsInt();
			else if (name.equals("DocumentNo"))
			{
				m_DocumentNo_From = (String)para[i].getParameter();
				m_DocumentNo_To = (String)para[i].getParameter_To();
			}
			else
				log.log(Level.SEVERE, "prepare - Unknown Parameter: " + name);
		}
		if (m_DocumentNo_From != null && m_DocumentNo_From.length() == 0)
			m_DocumentNo_From = null;
		if (m_DocumentNo_To != null && m_DocumentNo_To.length() == 0)
			m_DocumentNo_To = null;
	}	//	prepare

	/**
	 *  Perrform process.
	 *  @return Message
	 *  @throws Exception
	 */
	protected String doIt() throws java.lang.Exception
	{
		//	Need to have Template
		if (p_EMailPDF && p_R_MailText_ID == 0)
			throw new AdempiereUserError ("@NotFound@: @R_MailText_ID@");
		log.info ("C_BPartner_ID=" + m_C_BPartner_ID
			+ ", C_Invoice_ID=" + m_C_Invoice_ID
			+ ", EmailPDF=" + p_EMailPDF + ",R_MailText_ID=" + p_R_MailText_ID
			+ ", DateInvoiced=" + m_dateInvoiced_From + "-" + m_dateInvoiced_To
			+ ", DocumentNo=" + m_DocumentNo_From + "-" + m_DocumentNo_To);
		
		MMailText mText = null;
		if (p_R_MailText_ID != 0)
		{
			mText = new MMailText(getCtx(), p_R_MailText_ID, get_TrxName());
			if (mText.get_ID() != p_R_MailText_ID)
				throw new AdempiereUserError ("@NotFound@: @R_MailText_ID@ - " + p_R_MailText_ID);
		}

		//	Too broad selection
		if (m_C_BPartner_ID == 0 && m_C_Invoice_ID == 0 && m_dateInvoiced_From == null && m_dateInvoiced_To == null
			&& m_DocumentNo_From == null && m_DocumentNo_To == null)
			throw new AdempiereUserError ("@RestrictSelection@");

		MClient client = MClient.get(getCtx());
		
		//	Get Info
		StringBuffer sql = new StringBuffer (
			"SELECT i.C_Invoice_ID,bp.AD_Language,c.IsMultiLingualDocument,"		//	1..3
			//	Prio: 1. BPartner 2. DocType, 3. PrintFormat (Org)	//	see ReportCtl+MInvoice
			+ " COALESCE(bp.Invoice_PrintFormat_ID, dt.AD_PrintFormat_ID, pf.Invoice_PrintFormat_ID),"	//	4 
			+ " dt.DocumentCopies+bp.DocumentCopies,"								//	5
			+ " bpc.AD_User_ID, i.DocumentNo,"										//	6..7
			+ " bp.C_BPartner_ID "													//	8
			+ "FROM C_Invoice i"
			+ " INNER JOIN C_BPartner bp ON (i.C_BPartner_ID=bp.C_BPartner_ID)"
			+ " LEFT OUTER JOIN AD_User bpc ON (i.AD_User_ID=bpc.AD_User_ID)"
			+ " INNER JOIN AD_Client c ON (i.AD_Client_ID=c.AD_Client_ID)"
			+ " INNER JOIN AD_PrintForm pf ON (i.AD_Client_ID=pf.AD_Client_ID)"
			+ " INNER JOIN C_DocType dt ON (i.C_DocType_ID=dt.C_DocType_ID)"
		    + " WHERE i.AD_Client_ID=? AND i.AD_Org_ID=? AND i.isSOTrx='Y' AND "
		    + "       pf.AD_Org_ID IN (0,i.AD_Org_ID) AND " );	//	more them 1 PF
		boolean needAnd = false;
		if (m_C_Invoice_ID != 0)
			sql.append("i.C_Invoice_ID=").append(m_C_Invoice_ID);
		else
		{
			if (m_C_BPartner_ID != 0)
			{
				sql.append ("i.C_BPartner_ID=").append (m_C_BPartner_ID);
				needAnd = true;
			}
			if (m_dateInvoiced_From != null && m_dateInvoiced_To != null)
			{
				if (needAnd)
					sql.append(" AND ");
				sql.append("TRUNC(i.DateInvoiced, 'DD') BETWEEN ")
					.append(DB.TO_DATE(m_dateInvoiced_From, true)).append(" AND ")
					.append(DB.TO_DATE(m_dateInvoiced_To, true));
				needAnd = true;
			}
			else if (m_dateInvoiced_From != null)
			{
				if (needAnd)
					sql.append(" AND ");
				sql.append("TRUNC(i.DateInvoiced, 'DD') >= ")
					.append(DB.TO_DATE(m_dateInvoiced_From, true));
				needAnd = true;
			}
			else if (m_dateInvoiced_To != null)
			{
				if (needAnd)
					sql.append(" AND ");
				sql.append("TRUNC(i.DateInvoiced, 'DD') <= ")
					.append(DB.TO_DATE(m_dateInvoiced_To, true));
				needAnd = true;
			}
			else if (m_DocumentNo_From != null && m_DocumentNo_To != null)
			{
				if (needAnd)
					sql.append(" AND ");
				sql.append("i.DocumentNo BETWEEN ")
					.append(DB.TO_STRING(m_DocumentNo_From)).append(" AND ")
					.append(DB.TO_STRING(m_DocumentNo_To));
			}
			else if (m_DocumentNo_From != null)
			{
				if (needAnd)
					sql.append(" AND ");
				if (m_DocumentNo_From.indexOf('%') == -1)
					sql.append("i.DocumentNo >= ")
						.append(DB.TO_STRING(m_DocumentNo_From));
				else
					sql.append("i.DocumentNo LIKE ")
						.append(DB.TO_STRING(m_DocumentNo_From));
			}
			
			if (p_EMailPDF)
			{
				if (needAnd)
				{
					sql.append(" AND ");
				}
				/* if emailed to customer only select COmpleted & CLosed invoices */ 
				sql.append("i.DocStatus IN ('CO','CL') "); 
			}
		}
		sql.append(" ORDER BY i.C_Invoice_ID, pf.AD_Org_ID DESC");	//	more than 1 PF record
		log.fine(sql.toString());

		MPrintFormat format = null;
		int old_AD_PrintFormat_ID = -1;
		int old_C_Invoice_ID = -1;
		int C_BPartner_ID = 0;
		int count = 0;
		int errors = 0;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		List<File> pdfList = new ArrayList<File>();
		try
		{
			pstmt = DB.prepareStatement(sql.toString(), get_TrxName()); 
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setInt(2, Env.getAD_Org_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
						
			while (rs.next())
			{
				int invoiceId = rs.getInt(1);
				if (invoiceId == old_C_Invoice_ID)	//	multiple pf records
					continue;
				old_C_Invoice_ID = invoiceId;
				//	Set Language when enabled
				Language language = Language.getLoginLanguage();		//	Base Language
				String AD_Language = rs.getString(2);
				if (AD_Language != null && "Y".equals(rs.getString(3)))
					language = Language.getLanguage(AD_Language);
				//
				int AD_PrintFormat_ID = rs.getInt(4);
				int copies = rs.getInt(5);
				if (copies == 0)
					copies = 1;
				int AD_User_ID = rs.getInt(6);
				MUser to = new MUser (getCtx(), AD_User_ID, get_TrxName());
				String DocumentNo = rs.getString(7);
				C_BPartner_ID = rs.getInt(8);
				//
				String documentDir = client.getDocumentDir();
				if (documentDir == null || documentDir.length() == 0)
					documentDir = ".";
				//
				if (p_EMailPDF && (to.get_ID() == 0 || to.getEMail() == null || to.getEMail().length() == 0))
				{
					addLog (invoiceId, null, null, DocumentNo + " @RequestActionEMailNoTo@");
					errors++;
					continue;
				}
				if (AD_PrintFormat_ID == 0)
				{
					addLog (invoiceId, null, null, DocumentNo + " No Print Format");
					errors++;
					continue;
				}
				//	Get Format & Data
				if (AD_PrintFormat_ID != old_AD_PrintFormat_ID)
				{
					format = MPrintFormat.get (getCtx(), AD_PrintFormat_ID, false);
					old_AD_PrintFormat_ID = AD_PrintFormat_ID;
				}
				format.setLanguage(language);
				format.setTranslationLanguage(language);
				//	query
				MQuery query = new MQuery("C_Invoice_Header_v");
				query.addRestriction("C_Invoice_ID", MQuery.EQUAL, new Integer(invoiceId));

				//	Engine
				PrintInfo info = new PrintInfo(
					DocumentNo,
					X_C_Invoice.Table_ID,
					invoiceId,
					C_BPartner_ID);
				info.setCopies(copies);
				ReportEngine re = new ReportEngine(getCtx(), format, query, info);
				boolean printed = false;
				if (p_EMailPDF) {
					String subject = mText.getMailHeader() + " - " + DocumentNo;
					mText.setUser(to);					//	Context
					mText.setBPartner(C_BPartner_ID);	//	Context
					mText.setPO(new MInvoice(getCtx(), invoiceId, get_TrxName()));
					String message = mText.getMailText(true);
					//
					File invoice = null;
					if (!Ini.isClient()) {
						invoice = new File(MInvoice.getPDFFileName(documentDir, invoiceId));
					}
					File attachment = re.getPDF(invoice);
					log.fine(to + " - " + attachment);
					//	Get instance for notifier
					DefaultNotifier notifier = (DefaultNotifier) QueueLoader.getInstance().getQueueManager(DefaultNotifier.QUEUETYPE_DefaultNotifier)
							.withContext(Env.getCtx())
							.withTransactionName(get_TrxName());
					//	Send notification to queue
					notifier
						.clearMessage()
						.withApplicationType(DefaultNotifier.DefaultNotificationType_UserDefined)
						.withUserId(getAD_User_ID())
						.addRecipient(to.getAD_User_ID())
						.withText(message)
						.addAttachment(attachment)
						.withDescription(subject)
						.withTableId(MInvoice.Table_ID)
						.withRecordId(invoiceId);
					//	Add to queue
					notifier.addToQueue();
					count++;
					printed = true;
				}
				else
				{
					if (re != null)
						pdfList.add(re.getPDF());
					count++;
					printed = true;
				}
				//	Print Confirm
				if (printed)
				{
					StringBuffer sb = new StringBuffer ("UPDATE C_Invoice "
						+ "SET DatePrinted=SysDate, IsPrinted='Y' WHERE C_Invoice_ID=")
						.append (invoiceId);
					DB.executeUpdateEx(sb.toString(), get_TrxName());
				}
			}	//	for all entries						
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "doIt - " + sql, e);
			throw new Exception (e);
		}
		finally {
			DB.close(rs, pstmt);
		}
		
		if (pdfList.size() > 1) {
			try {
				File outFile = File.createTempFile("InvoicePrint", ".pdf");					
				AEnv.mergePdf(pdfList, outFile);

				Clients.showBusy(null, false);
				Window win = new SimplePDFViewer(this.getName(), new FileInputStream(outFile));
				win.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
				SessionManager.getAppDesktop().showWindow(win, "center");
			} catch (Exception e) {
				log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			}
		} else if (pdfList.size() > 0) {
			Clients.showBusy(null, false);
			try {
				Window win = new SimplePDFViewer(this.getName(), new FileInputStream(pdfList.get(0)));
				win.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
				SessionManager.getAppDesktop().showWindow(win, "center");
			} catch (Exception e)
			{
				log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			}
		}
		
		//
		if (p_EMailPDF)
			return "@Sent@=" + count + " - @Errors@=" + errors;
		return "@Printed@=" + count;
	}	//	doIt

}	//	InvoicePrint
