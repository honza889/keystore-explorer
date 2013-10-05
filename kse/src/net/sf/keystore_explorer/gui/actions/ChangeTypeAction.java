/*
 * Copyright 2004 - 2013 Wayne Grant
 *           2013 Kai Kramer
 *
 * This file is part of KeyStore Explorer.
 *
 * KeyStore Explorer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeyStore Explorer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeyStore Explorer.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.keystore_explorer.gui.actions;

import static net.sf.keystore_explorer.crypto.Password.getPkcs12DummyPassword;

import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.text.MessageFormat;
import java.util.Enumeration;

import javax.swing.JOptionPane;

import net.sf.keystore_explorer.crypto.Password;
import net.sf.keystore_explorer.crypto.keystore.KeyStoreType;
import net.sf.keystore_explorer.crypto.keystore.KeyStoreUtil;
import net.sf.keystore_explorer.crypto.x509.X509CertUtil;
import net.sf.keystore_explorer.gui.KseFrame;
import net.sf.keystore_explorer.gui.error.DError;
import net.sf.keystore_explorer.utilities.history.HistoryAction;
import net.sf.keystore_explorer.utilities.history.KeyStoreHistory;
import net.sf.keystore_explorer.utilities.history.KeyStoreState;

/**
 * Action to change the active KeyStore's type.
 * 
 */
public class ChangeTypeAction extends KeyStoreExplorerAction implements HistoryAction {
	private KeyStoreType newType;

	/**
	 * Construct action.
	 * 
	 * @param kseFrame
	 *            KeyStore Explorer frame
	 * @param newType
	 *            New KeyStore type
	 */
	public ChangeTypeAction(KseFrame kseFrame, KeyStoreType newType) {
		super(kseFrame);

		this.newType = newType;

		putValue(LONG_DESCRIPTION,
				MessageFormat.format(res.getString("ChangeTypeAction.statusbar"), newType.friendly()));
		putValue(NAME, newType.friendly());
		putValue(SHORT_DESCRIPTION, newType.friendly());
	}

	public String getHistoryDescription() {
		return MessageFormat.format(res.getString("ChangeTypeAction.History.text"), newType.friendly());
	}

	/**
	 * Do action.
	 */
	protected void doAction() {
		KeyStoreType currentType = KeyStoreType.resolveJce(kseFrame.getActiveKeyStoreHistory().getCurrentState()
				.getKeyStore().getType());

		if (currentType == newType) {
			return;
		}

		boolean changeResult = changeKeyStoreType(newType);

		if (!changeResult) {
			// Change type failed or cancelled - revert radio button menu item for KeyStore type
			kseFrame.updateControls(false);
		}
	}

	private boolean changeKeyStoreType(KeyStoreType newKeyStoreType) {
		try {
			KeyStoreHistory history = kseFrame.getActiveKeyStoreHistory();
			KeyStoreState currentState = history.getCurrentState();

			KeyStore currentKeyStore = currentState.getKeyStore();
			String currentType = currentState.getKeyStore().getType();

			KeyStore newKeyStore = KeyStoreUtil.create(newKeyStoreType);

			// Only warn the user once about key pair entry passwords when changing from PKCS #12
			boolean warnPkcs12Password = false;

			// Only warn the user once about key entries not being carried over by the change
			boolean warnNoChangeKey = false;

			// Copy all entries to the new KeyStore
			for (Enumeration aliases = currentKeyStore.aliases(); aliases.hasMoreElements();) {
				String alias = (String) aliases.nextElement();

				if (KeyStoreUtil.isTrustedCertificateEntry(alias, currentKeyStore)) {
					Certificate trustedCertificate = currentKeyStore.getCertificate(alias);
					newKeyStore.setCertificateEntry(alias, trustedCertificate);
				} else if (KeyStoreUtil.isKeyPairEntry(alias, currentKeyStore)) {
					Certificate[] certificateChain = currentKeyStore.getCertificateChain(alias);
					certificateChain = X509CertUtil.orderX509CertChain(X509CertUtil
							.convertCertificates(certificateChain));

					Password password = getEntryPassword(alias, currentState);

					if (password == null) {
						return false;
					}

					Key privateKey = currentKeyStore.getKey(alias, password.toCharArray());

					currentState.setEntryPassword(alias, password);

					if (currentType.equals(KeyStoreType.PKCS12.jce())) {
						if (!warnPkcs12Password) {
							warnPkcs12Password = true;
							JOptionPane.showMessageDialog(frame, MessageFormat.format(res
									.getString("ChangeTypeAction.ChangeFromPkcs12Password.message"), new String(
									getPkcs12DummyPassword().toCharArray())), res
									.getString("ChangeTypeAction.ChangeKeyStoreType.Title"),
									JOptionPane.INFORMATION_MESSAGE);
						}

						password = getPkcs12DummyPassword(); // Changing from PKCS #12 - password is 'password'
					} else if (newKeyStoreType == KeyStoreType.PKCS12) {
						password = getPkcs12DummyPassword(); // Changing to PKCS #12 - password is 'password'
					}

					newKeyStore.setKeyEntry(alias, privateKey, password.toCharArray(), certificateChain);
				} else if (KeyStoreUtil.isKeyEntry(alias, currentKeyStore)) {
					
					if (newKeyStoreType.supportsKeyEntries()) {
						
						Password password = getEntryPassword(alias, currentState);
						
						if (password == null) {
							return false;
						}
						
						Key secretKey = currentKeyStore.getKey(alias, password.toCharArray());
						
						currentState.setEntryPassword(alias, password);
						
						newKeyStore.setKeyEntry(alias, secretKey, password.toCharArray(), null);
					} else if (!warnNoChangeKey) {
						warnNoChangeKey = true;
						int selected = JOptionPane.showConfirmDialog(frame,
								res.getString("ChangeTypeAction.WarnNoChangeKey.message"),
								res.getString("ChangeTypeAction.ChangeKeyStoreType.Title"), JOptionPane.YES_NO_OPTION);
						if (selected != JOptionPane.YES_OPTION) {
							return false;
						}
					}
				}
			}

			KeyStoreState newState = currentState.createBasisForNextState(this);

			newState.setKeyStore(newKeyStore);

			// If changing type to be PKCS #12 or from PKCS #12 then all key
			// pair passwords are reset to be 'password'
			if ((newKeyStoreType == KeyStoreType.PKCS12) || (currentType.equals(KeyStoreType.PKCS12.jce()))) {
				Enumeration<String> aliases = newKeyStore.aliases();

				while (aliases.hasMoreElements()) {
					String alias = aliases.nextElement();

					if (KeyStoreUtil.isKeyPairEntry(alias, newKeyStore)) {
						newState.setEntryPassword(alias, getPkcs12DummyPassword());
					}
				}
			}

			currentState.append(newState);

			kseFrame.updateControls(true);

			JOptionPane.showMessageDialog(frame,
					res.getString("ChangeTypeAction.ChangeKeyStoreTypeSuccessful.message"),
					res.getString("ChangeTypeAction.ChangeKeyStoreType.Title"), JOptionPane.INFORMATION_MESSAGE);
			return true;
		} catch (Exception ex) {
			DError.displayError(frame, ex);
			return false;
		}
	}
}